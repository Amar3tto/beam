/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.sql.impl;

import static org.apache.beam.sdk.util.Preconditions.checkStateNotNull;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkNotNull;

import java.sql.SQLException;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.extensions.sql.BeamSqlUdf;
import org.apache.beam.sdk.extensions.sql.impl.QueryPlanner.QueryParameters;
import org.apache.beam.sdk.extensions.sql.impl.parser.BeamSqlParser;
import org.apache.beam.sdk.extensions.sql.impl.planner.BeamRuleSets;
import org.apache.beam.sdk.extensions.sql.impl.rel.BeamRelNode;
import org.apache.beam.sdk.extensions.sql.meta.BeamSqlTable;
import org.apache.beam.sdk.extensions.sql.meta.catalog.CatalogManager;
import org.apache.beam.sdk.extensions.sql.meta.catalog.InMemoryCatalog;
import org.apache.beam.sdk.extensions.sql.meta.catalog.InMemoryCatalogManager;
import org.apache.beam.sdk.extensions.sql.meta.provider.ReadOnlyTableProvider;
import org.apache.beam.sdk.extensions.sql.meta.provider.TableProvider;
import org.apache.beam.sdk.extensions.sql.meta.provider.UdfUdafProvider;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Combine.CombineFn;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.plan.RelOptUtil;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.schema.Function;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlKind;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.tools.RuleSet;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Strings;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Contains the metadata of tables/UDF functions, and exposes APIs to
 * query/validate/optimize/translate SQL statements.
 */
@Internal
@SuppressWarnings({
  "rawtypes", // TODO(https://github.com/apache/beam/issues/20447)
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class BeamSqlEnv {
  JdbcConnection connection;
  QueryPlanner planner;

  private BeamSqlEnv(JdbcConnection connection, QueryPlanner planner) {
    this.connection = connection;
    this.planner = planner;
  }

  /** Creates a builder with the default schema backed by the table provider. */
  public static BeamSqlEnvBuilder builder(TableProvider tableProvider) {
    return new BeamSqlEnvBuilder(tableProvider);
  }

  /** Creates a builder with the default schema backed by the catalog manager. */
  public static BeamSqlEnvBuilder builder(CatalogManager catalogManager) {
    return new BeamSqlEnvBuilder(catalogManager);
  }

  /**
   * This method creates {@link org.apache.beam.sdk.extensions.sql.impl.BeamSqlEnv} using empty
   * Pipeline Options. It should only be used in tests.
   */
  public static BeamSqlEnv readOnly(String tableType, Map<String, BeamSqlTable> tables) {
    return withTableProvider(new ReadOnlyTableProvider(tableType, tables));
  }

  /**
   * This method creates {@link org.apache.beam.sdk.extensions.sql.impl.BeamSqlEnv} using empty
   * Pipeline Options. It should only be used in tests.
   */
  public static BeamSqlEnv withTableProvider(TableProvider tableProvider) {
    return builder(tableProvider).setPipelineOptions(PipelineOptionsFactory.create()).build();
  }

  /**
   * This method creates {@link org.apache.beam.sdk.extensions.sql.impl.BeamSqlEnv} using empty *
   * Pipeline Options. It should only be used in tests.
   */
  public static BeamSqlEnv inMemory(TableProvider... tableProviders) {
    InMemoryCatalogManager catalogManager = new InMemoryCatalogManager();
    for (TableProvider tableProvider : tableProviders) {
      catalogManager.registerTableProvider(tableProvider);
    }

    return BeamSqlEnv.builder(catalogManager)
        .setPipelineOptions(PipelineOptionsFactory.create())
        .build();
  }

  public BeamRelNode parseQuery(String query) throws ParseException {
    return planner.convertToBeamRel(query, QueryParameters.ofNone());
  }

  public BeamRelNode parseQuery(String query, QueryParameters queryParameters)
      throws ParseException {
    return planner.convertToBeamRel(query, queryParameters);
  }

  public boolean isDdl(String sqlStatement) throws ParseException {
    return planner.parse(sqlStatement).getKind().belongsTo(SqlKind.DDL);
  }

  public void executeDdl(String sqlStatement) throws ParseException {
    BeamSqlParser.DDL_EXECUTOR.executeDdl(getContext(), planner.parse(sqlStatement));
  }

  public CalcitePrepare.Context getContext() {
    return connection.createPrepareContext();
  }

  public Map<String, String> getPipelineOptions() {
    return connection.getPipelineOptionsMap();
  }

  public String explain(String sqlString) throws ParseException {
    try {
      return RelOptUtil.toString(planner.convertToBeamRel(sqlString, QueryParameters.ofNone()));
    } catch (Exception e) {
      throw new ParseException("Unable to parse statement", e);
    }
  }

  /** BeamSqlEnv's Builder. */
  public static class BeamSqlEnvBuilder {
    private static final String CALCITE_PLANNER =
        "org.apache.beam.sdk.extensions.sql.impl.CalciteQueryPlanner";
    private String queryPlannerClassName;
    private @Nullable TableProvider defaultTableProvider;
    private CatalogManager catalogManager;
    private String currentSchemaName;
    private Map<String, TableProvider> schemaMap;
    private Set<Map.Entry<String, Function>> functionSet;
    private boolean autoLoadUdfs;
    private PipelineOptions pipelineOptions;
    private Collection<RuleSet> ruleSets;

    private BeamSqlEnvBuilder(TableProvider tableProvider) {
      checkNotNull(tableProvider, "Table provider for the default schema must be sets.");

      defaultTableProvider = tableProvider;
      catalogManager = new InMemoryCatalogManager();
      queryPlannerClassName = CALCITE_PLANNER;
      schemaMap = new HashMap<>();
      functionSet = new HashSet<>();
      autoLoadUdfs = false;
      pipelineOptions = null;
      ruleSets = BeamRuleSets.getRuleSets();
    }

    private BeamSqlEnvBuilder(CatalogManager catalogManager) {
      checkNotNull(catalogManager, "Catalog manager for the default schema must be set.");

      this.catalogManager = catalogManager;
      this.queryPlannerClassName = CALCITE_PLANNER;
      this.schemaMap = new HashMap<>();
      this.functionSet = new HashSet<>();
      this.autoLoadUdfs = false;
      this.pipelineOptions = null;
      this.ruleSets = BeamRuleSets.getRuleSets();
    }

    /** Add a top-level schema backed by the table provider. */
    public BeamSqlEnvBuilder addSchema(String name, TableProvider tableProvider) {
      if (schemaMap.containsKey(name)) {
        throw new RuntimeException("Schema " + name + " is registered twice.");
      }

      schemaMap.put(name, tableProvider);
      return this;
    }

    /** Set the current (default) schema. */
    public BeamSqlEnvBuilder setCurrentSchema(String name) {
      currentSchemaName = name;
      return this;
    }

    /** Set the ruleSet used for query optimizer. */
    public BeamSqlEnvBuilder setRuleSets(Collection<RuleSet> ruleSets) {
      this.ruleSets = ruleSets;
      return this;
    }
    /** Register a UDF function which can be used in SQL expression. */
    public BeamSqlEnvBuilder addUdf(String functionName, Class<?> clazz, String method) {
      functionSet.add(new SimpleEntry<>(functionName, UdfImpl.create(clazz, method)));
      return this;
    }

    /** Register a UDF function which can be used in SQL expression. */
    public BeamSqlEnvBuilder addUdf(String functionName, Class<? extends BeamSqlUdf> clazz) {
      return addUdf(functionName, clazz, BeamSqlUdf.UDF_METHOD);
    }

    /** Register a UDF function which can be used in SQL expression. */
    public BeamSqlEnvBuilder addUdf(String functionName, SerializableFunction sfn) {
      return addUdf(functionName, sfn.getClass(), "apply");
    }

    /**
     * Register a UDAF function which can be used in GROUP-BY expression.
     *
     * <p>See {@link CombineFn} on how to implement a UDAF.
     */
    public BeamSqlEnvBuilder addUdaf(String functionName, CombineFn combineFn) {
      functionSet.add(new SimpleEntry<>(functionName, new UdafImpl(combineFn)));
      return this;
    }

    /** Load UDF/UDAFs from {@link UdfUdafProvider}. */
    public BeamSqlEnvBuilder autoLoadUserDefinedFunctions() {
      autoLoadUdfs = true;
      return this;
    }

    public BeamSqlEnvBuilder setQueryPlannerClassName(String name) {
      queryPlannerClassName = name;
      return this;
    }

    public BeamSqlEnvBuilder setPipelineOptions(PipelineOptions pipelineOptions) {
      this.pipelineOptions = pipelineOptions;
      return this;
    }

    /**
     * Uses the specified {@link InMemoryCatalog}. Must already exist inside the provided {@link
     * CatalogManager}.
     */
    public BeamSqlEnvBuilder setUseCatalog(String name) {
      catalogManager.useCatalog(name);
      return this;
    }

    /**
     * Build function to create an instance of BeamSqlEnv based on preset fields.
     *
     * @return BeamSqlEnv.
     */
    public BeamSqlEnv build() {
      checkStateNotNull(pipelineOptions);

      JdbcConnection jdbcConnection;
      if (defaultTableProvider != null) {
        jdbcConnection = JdbcDriver.connect(defaultTableProvider, pipelineOptions);
      } else {
        jdbcConnection = JdbcDriver.connect(catalogManager, pipelineOptions);
      }

      configureSchemas(jdbcConnection);

      QueryPlanner planner = instantiatePlanner(jdbcConnection, ruleSets);

      // The planner may choose to add its own builtin functions to the schema, so load user-defined
      // functions second, in case there's a conflict.
      loadUdfs();
      addUdfsUdafs(jdbcConnection);

      return new BeamSqlEnv(jdbcConnection, planner);
    }

    private void configureSchemas(JdbcConnection jdbcConnection) {
      // SetSchema adds the schema with the specified name
      // backed by the table provider.
      // Does not update the current default schema.
      schemaMap.forEach(jdbcConnection::setSchema);

      if (Strings.isNullOrEmpty(currentSchemaName)) {
        return;
      }

      try {
        jdbcConnection.setSchema(currentSchemaName);
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

    private void loadUdfs() {
      if (!autoLoadUdfs) {
        return;
      }

      ServiceLoader.load(UdfUdafProvider.class)
          .forEach(
              ins -> {
                ins.getBeamSqlUdfs().forEach(this::addUdf);
                ins.getSerializableFunctionUdfs().forEach(this::addUdf);
                ins.getUdafs().forEach(this::addUdaf);
              });
    }

    private void addUdfsUdafs(JdbcConnection connection) {
      for (Map.Entry<String, Function> functionEntry : functionSet) {
        connection.getCurrentSchemaPlus().add(functionEntry.getKey(), functionEntry.getValue());
      }
    }

    private QueryPlanner instantiatePlanner(
        JdbcConnection jdbcConnection, Collection<RuleSet> ruleSets) {
      Class<?> queryPlannerClass;
      try {
        queryPlannerClass = Class.forName(queryPlannerClassName);
      } catch (ClassNotFoundException exc) {
        throw new RuntimeException(
            "Cannot find requested QueryPlanner class: " + queryPlannerClassName, exc);
      }

      QueryPlanner.Factory factory;
      try {
        factory = (QueryPlanner.Factory) queryPlannerClass.getField("FACTORY").get(null);
      } catch (NoSuchFieldException | IllegalAccessException exc) {
        throw new RuntimeException(
            String.format(
                "QueryPlanner class %s does not have an accessible static field 'FACTORY' of type QueryPlanner.Factory",
                queryPlannerClassName),
            exc);
      }

      return factory.createPlanner(jdbcConnection, ruleSets);
    }
  }
}
