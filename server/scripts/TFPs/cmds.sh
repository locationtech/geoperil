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

# arguments are sometimes needed for a reasonable result
pdf2txt -M 30 -W .95 -L .03 cwave14.pdf > cwave14.txt
pdf2txt cwave15.pdf > cwave15.txt
pdf2txt cwave16.pdf > cwave16.txt
pdf2txt pwave_ts.pdf > pwave_ts.txt
pdf2txt caribe_annex.pdf > caribe_annex.txt
pdf2txt -M 30 -W .95 -L .03 ptws.pdf | grep -E "JUN|AUG|SEP" > ptws.txt

# create pwave16_table.txt manually by copying the contents of Table 1 from page 13f of pwave16.pdf
# afterwards, run the following command
cat pwave16_table.txt | tr '\n' '@' | sed 's/@/  /g' | sed -r 's/([0-9]+)  /\1\n/g' > pwave16.txt

# create pwave_table.txt manually by copying the contents of Table ? on page 57(63) from pwave_ts.pdf
# create ptws_table.txt manually by copying the contents of Table 4.3 on page 38(50) from ptws.pdf

python3 ./extract.py cwave14.txt -cw14 -src cwave14.pdf #-w
python3 ./extract.py cwave15.txt -cw15 -src cwave15.pdf #-w
python3 ./extract.py cwave16.txt -cw16 -src cwave16.pdf #-w
python3 ./extract.py caribe_annex.txt -cw16 -src caribe_annex.pdf #-w

python3 ./extract.py pwave16.txt -pw16 -src pwave16.pdf #-w
python3 ./extract.py pwave_table.txt -pw16 -src pwave_ts.pdf #-w
python3 ./extract.py pwave_ts.txt -cw16 -src pwave_ts.pdf #-w
python3 ./extract.py ptws_table.txt -pw16 -src ptws.pdf #-w
python3 ./extract.py ptws.txt -pw16 -src ptws.pdf #-w

# gauge locations
python3 ./gauge.py cwave16.txt -gauge -dist 0.1 -v #-w
