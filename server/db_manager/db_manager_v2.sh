#!/bin/bash

# GeoPeril - A platform for the computation and web-mapping of hazard specific
# geospatial data, as well as for serving functionality to handle, share, and
# communicate threat specific information in a collaborative environment.
#
# Copyright (C) 2013 GFZ German Research Centre for Geosciences
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the Licence is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the Licence for the specific language governing permissions and
# limitations under the Licence.
#
# Contributors:
# Johannes Spazier (GFZ) - initial implementation
# Sven Reissland (GFZ) - initial implementation
# Martin Hammitzsch (GFZ) - initial implementation
# Hannes Fuchs (GFZ) - refactoring

LOCKFILE_DIR="/var/run/lock/db_manager_v2"

cd `dirname $0`

if [ ! -d "$LOCKFILE_DIR" ]; then
  mkdir "$LOCKFILE_DIR"
fi

if [ ! -e "${LOCKFILE_DIR}/running.lock" ]; then
  touch "${LOCKFILE_DIR}/running.lock"
  ./manager_v2.py
  rm "${LOCKFILE_DIR}/running.lock"
fi
