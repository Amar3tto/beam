# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os
import time
import looker_sdk

from google.cloud import storage
from looker_sdk import models40 as models

# Load environment variables
LOOKER_API_URL = os.getenv("LOOKERSDK_BASE_URL")
LOOKER_CLIENT_ID = os.getenv("LOOKERSDK_CLIENT_ID")
LOOKER_CLIENT_SECRET = os.getenv("LOOKERSDK_CLIENT_SECRET")
TARGET_BUCKET = os.getenv("GCS_BUCKET")

# List of Look IDs to download
LOOKS_TO_DOWNLOAD = ["116", "22"]


def get_look(id: str) -> models.Look:
    look = next(iter(sdk.search_looks(id=id)), None)
    if not look:
        raise Exception(f"look '{id}' was not found")
    return look


def download_look(look: models.Look):
    """Download specified look as png/jpg"""
    task = sdk.create_look_render_task(look.id, "png", 810, 526,)

    if not (task and task.id):
        raise Exception(
            f"Could not create a render task for '{look.title}'"
        )

    # poll the render task until it completes
    elapsed = 0.0
    delay = 0.5  # wait .5 seconds
    content = sdk.render_task_results(task.id)
    while content is None or content == "":
        content = sdk.render_task_results(task.id)
        # if poll.status == "failure":
        #     print(poll)
        #     raise Exception(f"Render failed for '{look.id}'")
        # elif poll.status == "success":
        #     break
        time.sleep(delay)
        elapsed += delay
    print(f"Render task completed in {elapsed} seconds")

    return content


def upload_to_gcs(bucket_name, destination_blob_name, content):
    """Upload content to GCS bucket."""
    client = storage.Client()
    bucket = client.bucket(bucket_name)
    blob = bucket.blob(destination_blob_name)

    # Upload content, overwriting if it exists
    blob.upload_from_string(content, content_type="image/png")
    print(f"Uploaded {destination_blob_name} to {bucket_name}.")


sdk = looker_sdk.init40()


def main():

    for look_id in LOOKS_TO_DOWNLOAD:
        if look_id:
            look = get_look(look_id)
            content = download_look(look)
            if content:
                upload_to_gcs(TARGET_BUCKET, f"{look_id}.png", content)


if __name__ == "__main__":
    main()
