'''
   GeoPeril - A platform for the computation and web-mapping of hazard specific
   geospatial data, as well as for serving functionality to handle, share, and
   communicate threat specific information in a collaborative environment.

   Copyright (C) 2013 GFZ German Research Centre for Geosciences

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

     http://apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the Licence is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the Licence for the specific language governing permissions and
   limitations under the Licence.

   Contributors:
   Johannes Spazier (GFZ) - initial implementation
   Sven Reissland (GFZ) - initial implementation
   Martin Hammitzsch (GFZ) - initial implementation
'''

import re
import argparse
import datetime
import pycountry

from pymongo import MongoClient


def vprint(verbose, *_args, **_kwargs):
    if verbose:
        print(*_args, **_kwargs)


def main():
    client = MongoClient("mongodb://mongo")
    dbm = client['geoperil']

    parser = argparse.ArgumentParser(
        description='Extract tsunami forecast points from PDF.'
    )
    parser.add_argument('fname', type=str)
    parser.add_argument(
        '-cw14', dest='cw14',
        action='store_true', help='CARIBE WAVE 14'
    )
    parser.add_argument(
        '-cw15', dest='cw15',
        action='store_true', help='CARIBE WAVE 15'
    )
    parser.add_argument(
        '-cw16', dest='cw16',
        action='store_true', help='CARIBE WAVE 16'
    )
    parser.add_argument(
        '-pw16', dest='pw16',
        action='store_true', help='PACIFIC WAVE 16'
    )
    parser.add_argument(
        '-w', dest='write',
        action='store_true', help='Write results into DB.'
    )
    parser.add_argument(
        '-dup', dest='dup',
        action='store_true', help='Print duplicates.'
    )
    parser.add_argument(
        '-v', dest='verbose',
        action='store_true', help='Be verbose.'
    )
    parser.add_argument(
        '-src', dest='source', nargs='?',
        type=str, help='Add source document to database.'
    )
    args = parser.parse_args()

    file = open(args.fname, 'r')

    done = {}

    if args.cw14:
        regex = r'^\s*(?P<country>[a-zA-Z_ ]+) ' + \
            r'(?P<name>[a-zA-Z_]+)\s*(?P<lat>\d*\.\d*S?N?)\s*' + \
            r'(?P<lon>\d*\.\d*W?E?)\s*\d*Z.*$'
    elif args.cw15:
        regex = r'^\s*(?P<country>[a-zA-Z_ ]+)  ' + \
            r'(?P<name>[a-zA-Z][a-zA-Z_ ]*)  ' + \
            r'(?P<lat>\d*\.\d*S?N?)\s*(?P<lon>\d*\.\d*W?E?)\s*\d*Z.*$'
    elif args.cw16:
        regex = r'^\s*(?P<name>[a-zA-Z][-a-zA-Z_ ]+)  ' + \
            r'(?P<country>[a-zA-Z][a-zA-Z_() ]*)  ' + \
            r'(?P<lat>-?\d*\.\d*S?N?)\s*(?P<lon>-?\d*\.\d*W?E?).*$'
    elif args.pw16:
        regex = r'^\s*(?P<country>(?:[a-zA-Z_. ]+  )?)' + \
            r'(?P<name>[a-zA-Z][a-zA-Z_(). ]*)  ' + \
            r'(?P<lat>\d+\.\d+[SN]?)\s*(?P<lon>\d+\.\d+[WE]?).*$'
    else:
        raise ValueError('No format given.')

    now = datetime.datetime.utcnow()
    ninsert = 0

    for line in file:
        match = re.search(regex, line)
        if match:
            iso2 = None

            if not match.group('country').strip():
                pass
            else:
                match.group('country')
            # remove multiple blanks and replace with a single space
            country = " ".join(country.split())
            try:
                iso2 = pycountry.countries.get(
                    name=country.strip().title()
                ).alpha2
            except KeyError:
                pass

            lat = float(match.group('lat')[:-1]) * \
                (1 - 2 * match.group('lat').endswith("S"))
            lon = float(match.group('lon')[:-1]) * \
                (1 - 2 * match.group('lon').endswith("W"))
            obj = {
                "country": country.strip(),
                "name": match.group('name').strip().replace('_', ' '),
                "lat_real": lat,
                "lon_real": lon,
                "lat_sea": lat,
                "lon_sea": lon,
                "iso_2": iso2,
                "date": now
            }

            if str(obj) in done:
                if args.dup:
                    print(obj)
                continue

            done[str(obj)] = True

            prev = dbm["tfps"].find_one({"$or": [
                {"lat_real": obj["lat_real"], "lon_real": obj["lon_real"]},
                {"country": obj["country"], "name": obj["name"]}
            ]})
            if prev is not None:
                vprint(args.verbose, "#:", obj)
                if args.source is not None:
                    dbm["tfps"].update(
                        prev,
                        {"$addToSet": {"source": args.source}}
                    )
                continue

            if args.source is not None:
                obj["source"] = [args.source]

            print(obj)

            if args.write:
                dbm["tfps"].insert(obj)
                ninsert += 1

    file.close()
    vprint(args.verbose, '# %d records written.' % ninsert)


if __name__ == "__main__":
    main()
