/*
 * GeoPeril - A platform for the computation and web-mapping of hazard specific
 * geospatial data, as well as for serving functionality to handle, share, and
 * communicate threat specific information in a collaborative environment.
 *
 * Copyright (C) 2013 GFZ German Research Centre for Geosciences
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * Contributors:
 * Johannes Spazier (GFZ) - initial implementation
 * Sven Reissland (GFZ) - initial implementation
 * Martin Hammitzsch (GFZ) - initial implementation
 */

package GeoHazardServices;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;

import javax.inject.Singleton;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.CookieParam;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.bson.types.ObjectId;

import FloodPrototype.FloodProvider;
import FloodPrototype.FloodTask;
import FloodPrototype.Location;
import Misc.IDataProvider;
import Misc.User;
import Misc.GsonUTCdateAdapter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.mongodb.AggregationOutput;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;

import java.util.Base64;

class Inst extends User {
    public String secret;

    Inst(final DBObject obj) {
        super(obj);
        this.name = (String) obj.get("name");
        this.secret = (String) obj.get("secret");
        this.inst = this.name;
    }
}

class CompId {
    private String compId;
    public String inst;
    public String id;
    public long refineId;

    CompId(final String computeId) {
        this.compId = computeId;
        setSingleIds();
    }

    CompId(final String cinst, final String cid, final long crefineId) {
        this.inst = cinst;
        this.id = cid;
        this.refineId = crefineId;
        this.compId = getCompId();
    }

    private String getCompId() {
        return id + "_" + inst + "_" + refineId;
    }

    private void setSingleIds() {
        String[] parts = compId.split("_");

        int len = parts.length;

        inst = parts[--len];
        refineId = Integer.valueOf(parts[--len]);
        id = parts[ 0 ];

        for (int i = 1; i < len; i++) {
            id += "_" + parts[i];
        }
    }

    @Override
    public String toString() {
        return compId;
    }
}

class DateComparator implements Comparator<DBObject> {
    private String key;
    private int order;

    DateComparator(final String dckey, final int dcorder) {
        this.key = dckey;
        this.order = dcorder;
    }

    @Override
    public int compare(final DBObject o1, final DBObject o2) {
        Date d1 = (Date) o1.get(key);
        Date d2 = (Date) o2.get(key);

        return d1.compareTo(d2) * order;
    }
}

/* Use one global instance of this class instead of creating a new one for
 * each request */
@Path("")
@Singleton
public final class Services {
    private IScheduler scheduler;
    private ArrayList<WorkerThread> worker;

    private MongoClient mongoClient;
    private DB db;
    private Gson gson;

    private Map<String, Inst> institutions;

    private final int statusFailed = -1;
    private final int statusNoComp = -2;
    private final int statusAbort = -3;
    private final int progressFinished = 100;
    private final int defaultGridRes = 120;
    private final int convertGridRes = 60;
    private final int randomIdLength = 5;
    private final int totelDurationExtra = 10;
    private final int convertToSeconds = 60;
    private final int convertToMillis = 1000;
    private final int findEvtsetLimit = 100;

    private Map<String, IDataProvider> providers;

    public Services() {
        System.out.println("Constructor");
        Locale.setDefault(new Locale("en", "US"));
        scheduler = new FairScheduler();
        worker = new ArrayList<WorkerThread>();

        URL config;

        try {
            /* Try to load environment from context. */
            javax.naming.Context initCtx = new InitialContext();
            config = new File((String) initCtx.lookup("java:comp/env/config"))
                .toURI().toURL();
        } catch (NamingException e1) {
            /* Otherwise, use the local properties file. */
            config = this.getClass().getClassLoader()
                .getResource("config.properties");
        } catch (MalformedURLException e) {
            System.err.println("Malformed URL in environment context.");
            e.printStackTrace();
            return;
        }

        try {
            Properties prop = new Properties();
            prop.load(config.openStream());
            String dbname = prop.getProperty("db");
            String url = prop.getProperty("url");
            if (dbname == null || url == null) {
                throw new IOException("Could not load properties.");
            }

            String[] nodes = url.split(",");
            List<ServerAddress> addresses = new ArrayList<ServerAddress>();
            for (String node: nodes) {
                addresses.add(new ServerAddress(node));
            }

            System.out.println(addresses);

            mongoClient = new MongoClient(addresses);
            db = mongoClient.getDB(dbname);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
            return;
        }

        loadSettings();

        /* Start scheduler thread. */
        new Thread(scheduler).start();

        loadInstitutions();

        /* Required to deliver dates in UTC instead of simply dropping the
         * time zone as per default !!! */
        gson = new GsonBuilder().registerTypeAdapter(
                Date.class, new GsonUTCdateAdapter()
            ).create();

        /* TODO: find right place */
        providers = new HashMap<String, IDataProvider>();
        providers.put("floodsim", new FloodProvider(db));

        Listener.registerService(this);
    }

    public void destroy() {
        mongoClient.close();

        for (WorkerThread w: worker) {
            w.stop();
        }
    }

    private void loadSettings() {
        System.out.println("Load settings...");

        DBCollection coll = db.getCollection("settings");

        DBCursor cursor = coll.find(new BasicDBObject("type", "parameter"));
        for (DBObject obj: cursor) {
            String name = (String) obj.get("name");
            String value = (String) obj.get("value");

            GlobalParameter.map.put(name, value);
            System.out.println("Parameter " + name + ": " + value);
        }
        cursor.close();

        cursor = coll.find(new BasicDBObject("type", "jet_color"));
        for (DBObject obj: cursor) {
            Double threshold = Double.valueOf(obj.get("threshold").toString());
            String color = (String) obj.get("color");

            GlobalParameter.jets.put(threshold, color);
            System.out.println(
                "Tsunami-Jet-Threshold " + threshold + ": " + color
            );
        }
        cursor.close();

        DBObject urls = coll.findOne(new BasicDBObject("type", "urls"));
        GlobalParameter.wsgi_url = (String) urls.get("wsgi");

        int j = 0;

        cursor = coll.find(new BasicDBObject("type", "worker"));
        for (DBObject obj: cursor) {

            String hardware = (String) obj.get("hardware");
            String user = (String) obj.get("user");
            String host = (String) obj.get("host");
            String dir = (String) obj.get("dir");
            String args = (String) obj.get("args");
            /* MongoDB stores all integer values as Long (bug?), so convert
             * back here */
            Integer count;
            if (obj.get("count") instanceof Long) {
                count = ((Long) obj.get("count")).intValue();
            } else {
                count = (Integer) obj.get("count");
            }
            Integer priority;
            if (obj.get("priority") instanceof Long) {
                priority = ((Long) obj.get("priority")).intValue();
            } else {
                priority = (Integer) obj.get("priority");
            }
            boolean remote = (boolean) obj.get("remote");
            int[] slots = getSlots(obj.get("slots"), count);

            if (count == null) {
                count = 1;
            }

            System.out.print(
                "Worker " + count + "x " + hardware + " @ " + priority
            );
            if (remote) {
                System.out.print(", Remote: " + user + "@" + host + ":" + dir);
            }
            System.out.println(", Args: " + args);

            for (int i = 0; i < count; i++, j++) {
                WorkerThread thread;
                try {
                    thread = new WorkerThread(
                        scheduler,
                        mongoClient.getServerAddressList(),
                        db.getName(),
                        GlobalParameter.map.get("localdir") + "/w" + j
                    );
                } catch (IOException e) {
                    System.err.println(
                        "Error: Could not create worker thread."
                    );
                    e.printStackTrace();
                    continue;
                }

                thread.setHardware(hardware);
                thread.setArgs(args);
                thread.setRemote(user, host, dir + i);
                thread.setPriority(priority);
                thread.setSlot(slots[i]);

                worker.add(thread);
                thread.start();
            }

        }
        cursor.close();
    }

    /* Input: Either integer object with single slot value or an array of
     * values. */
    private int[] getSlots(final Object obj, final int count) {
        int[] slots = new int[count];
        if (obj instanceof Number) {
            Arrays.fill(slots, ((Number) obj).intValue());
        } else if (obj instanceof ArrayList<?>) {
            int idx = 0;
            for (Object slot: (ArrayList<?>) obj) {
                slots[idx++] = ((Number) slot).intValue();
            }
        }
        return slots;
    }

    private void loadInstitutions() {
        institutions = new HashMap<String, Inst>();

        DBCollection coll = db.getCollection("institutions");

        DBCursor cursor = coll.find();

        for (DBObject obj: cursor) {
            Inst inst = new Inst(obj);
            institutions.put(inst.name, inst);
        }

        cursor.close();
    }

    private boolean checkParams(
        final HttpServletRequest request,
        final Object[] list
    ) {
        for (Object o: list) {
            if (o == null) {
                return false;
            }
        }

        return true;
    }

    private Object getField(final DBObject obj, final String field) {
        String[] parts = field.split("\\.");
        Object sub = obj;
        for (String p: parts) {
            if (sub == null) {
                return null;
            }
            if (!p.matches("d+")) {
                sub = ((DBObject) sub).get(p);
            } else {
                try {
                    sub = ((BasicDBList) sub).get(Integer.valueOf(p));
                } catch (IndexOutOfBoundsException e) {
                    return null;
                }
            }
        }
        return sub;
    }

    private boolean checkPerm(final User user, final String perm) {
        if (user == null) {
            return false;
        }
        DBObject obj = db.getCollection("users").findOne(
            new BasicDBObject("_id", user.objId)
        );
        if (obj == null) {
            return false;
        }
        DBObject permObj = (DBObject) obj.get("permissions");
        if (permObj == null) {
            return false;
        }
        Boolean set = (Boolean) permObj.get(perm);
        if (set == null || !set) {
            return false;
        }
        return true;
    }

    private String computeByIdForUser(
        final User user,
        final String evtid,
        final Integer dur,
        final Integer accel,
        final Integer gridres,
        final String algo
    ) {
        DBObject eq = db.getCollection("eqs").findOne(
            new BasicDBObject("_id", evtid)
        );
        if (eq == null) {
            return null;
        }

        BasicDBObject process = new BasicDBObject("process", new BasicDBList());
        BasicDBObject set = new BasicDBObject("$set", process);
        db.getCollection("eqs").update(eq, set);

        /* extract properties to pass them to the request method */
        BasicDBObject prop = (BasicDBObject) eq.get("prop");
        double lat = prop.getDouble("latitude");
        double lon = prop.getDouble("longitude");
        double dip = prop.getDouble("dip");
        double strike = prop.getDouble("strike");
        double rake = prop.getDouble("rake");
        double depth = prop.getDouble("depth");
        Date date = prop.getDate("date");

        EQParameter eqp;
        double mag = 0.0;
        double slip = 0.0;
        double length = 0.0;
        double width = 0.0;
        if (prop.get("magnitude") == null) {
            slip = prop.getDouble("slip");
            length = prop.getDouble("length");
            width = prop.getDouble("width");
            eqp = new EQParameter(
                lon, lat, slip, length, width, depth, dip, strike, rake, date
            );
        } else {
            mag = prop.getDouble("magnitude");
            eqp = new EQParameter(
                lon, lat, mag, depth, dip, strike, rake, date
            );
        }

        Integer useAccel = accel;
        if (useAccel == null) {
            useAccel = 1;
        }

        /* start request */
        EQTask task = new EQTask(eqp, evtid, user, dur, useAccel, gridres);
        task.algo = algo;
        task.setSlots(IScheduler.SLOT_NORMAL, IScheduler.SLOT_EXCLUSIVE);
        return request(evtid, task);
    }

    @POST
    @Path("/computeById")
    @Produces(MediaType.APPLICATION_JSON)
    public String computeById(
        @Context final HttpServletRequest request,
        @FormParam("inst") final String inst,
        @FormParam("secret") final String secret,
        @FormParam("id") final String id,
        @FormParam("refineId") final Long refineId,
        @FormParam("dur") final Integer dur,
        @FormParam("accel") final Integer accel,
        @FormParam("apikey") final String apikey,
        @FormParam("evtid") final String evtid,
        @FormParam("raw") @DefaultValue("0") final Integer raw,
        @FormParam("gridres") final Integer gridres,
        @FormParam("dt_out") @DefaultValue("10") final Integer dt_out,
        @FormParam("algo") @DefaultValue("easywave") final String algo
    ) {
        String useApikey = apikey;
        Integer useAccel = accel;
        String useEvtid = evtid;
        Integer useDur = dur;
        Integer useGridres = gridres;

        /* Check for invalid parameter configurations. */
        if ((inst != null || secret != null) && useApikey != null) {
            return jsfailure("Don't mix 'apikey' and 'secret'.");
        }

        /* Support 'inst' and 'secret' for compatibility reasons. */
        if (inst != null && secret != null) {
            /* Obtain the 'apikey' and pretend a call to the new api. */
            DBObject query = new BasicDBObject("name", inst)
                .append("secret", secret);
            DBObject tmpInst = db.getCollection("institutions")
                .findOne(query);
            if (tmpInst == null) {
                return jsdenied();
            }
            useApikey = (String) ((DBObject) tmpInst.get("api")).get("key");
            if (useApikey == null) {
                return jsfailure("No 'apikey' set for this institution!");
            }
        }

        /* Authenticate user. */
        DBObject dbUser = authApi(useApikey, "user");
        DBObject dbInst = authApi(useApikey, "inst");

        User user;
        if (dbUser != null) {
            user = new User(dbUser, getInst(dbUser));
        } else if (dbInst != null) {
            user = new Inst(dbInst);
        } else {
            return jsdenied();
        }

        /* Check for invalid parameter configurations. */
        if ((id != null || refineId != null) && useEvtid != null) {
            return jsfailure("Don't mix 'id' and 'evtid'.");
        }

        if (useEvtid == null) {
            useEvtid = new CompId(inst, id, refineId).toString();
        }

        /* Check for missing parameters */
        if (useEvtid == null) {
            return jsfailure("Missing parameter.");
        }

        /* search for given id */
        BasicDBObject query = new BasicDBObject("_id", useEvtid)
            .append("user", user.objId);
        DBObject entry = db.getCollection("eqs").findOne(query);

        /* return if id not found */
        if (entry == null) {
            return jsfailure("Event ID not found.");
        }

        /* check if already computed */
        Integer progress = statusForEvtId(useEvtid, raw);
        if (progress != statusNoComp) {
            if (raw == 0) {
                return jsfailure("Re-computation not allowed.");
            }
            if (progress != progressFinished) {
                return jsfailure("A computation is currently running.");
            }
        }

        /* Use same duration as in original simulation if available. */
        if (useDur == null) {
            Number n = (Number) getField(entry, "process.0.simTime");
            /* Duration could not be determined. */
            if (n == null) {
                return jsfailure("Missing parameter.");
            }
            useDur = n.intValue();
        }

        /* Use grid resolution of original computation or default to 120
         * seconds. */
        if (useGridres == null) {
            Number res = (Number) getField(entry, "process.0.resolution");
            useGridres = res == null
                ? defaultGridRes
                : (int) (res.doubleValue() * convertGridRes);
        }

        /* get properties of returned entry */
        BasicDBObject prop = (BasicDBObject) entry.get("prop");

        BasicDBObject process = new BasicDBObject("raw_progress", 0);
        if (raw == 0) {
            process.append("process", new BasicDBList());
        }

        BasicDBObject set = new BasicDBObject("$set", process);
        db.getCollection("eqs").update(entry, set);

        /* extract properties to pass them to the request method */
        double lat = prop.getDouble("latitude");
        double lon = prop.getDouble("longitude");
        double mag = prop.getDouble("magnitude");
        double dip = prop.getDouble("dip");
        double strike = prop.getDouble("strike");
        double rake = prop.getDouble("rake");
        double depth = prop.getDouble("depth");
        Date date = prop.getDate("date");

        if (useAccel == null) {
            useAccel = 1;
        }

        /* prepare the simulation for execution */
        EQParameter eqp = new EQParameter(
            lon, lat, mag, depth, dip, strike, rake, date
        );
        EQTask task = new EQTask(
            eqp, useEvtid, user, useDur, useAccel, useGridres
        );
        task.raw = raw;
        task.dt_out = dt_out;
        task.algo = algo;
        String retId = request(useEvtid, task);
        return jssuccess(new BasicDBObject("_id", retId));
    }

    private String request(final String id, final Task task) {
        scheduler.submit(task);
        return id;
    }

    String newRandomId(final String username) {
        Random rand = new Random();
        String id;
        DBObject obj1;
        DBObject obj2;
        final String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        final int countLetters = alphabet.length();

        do {
            id = username + ".";
            for (int i = 0; i < randomIdLength; i++) {
                id += alphabet.charAt(rand.nextInt(countLetters));
            }
            obj1 = db.getCollection("eqs")
                .findOne(new BasicDBObject("_id", id));
            obj2 = db.getCollection("evtsets")
                .findOne(new BasicDBObject("_id", id));

        } while (obj1 != null || obj2 != null);

        return id;
    }

    private List<Double> rangeValues(
        final Double min,
        final Double step,
        final Double max
    ) {
        List<Double> values = new ArrayList<Double>();
        if (min == null || step == null || max == null) {
            return null;
        }
        /*int count = (int)( (max - min) / step) + 1;
        if( count <= 0 || count > 30 )
            return null;*/
        for (Double i = min; i <= max; i += step) {
            values.add(i);
        }
        return values;
    }

    private List<Double> listValues(final String str) {
        List<Double> list = new ArrayList<Double>();
        for (String s: str.split(",")) {
            try {
                list.add(Double.valueOf(s));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return list;
    }

    private List<Double> getValues(
        final String list,
        final Double min,
        final Double step,
        final Double max
    ) {
        if (list != null) {
            return listValues(list);
        }
        return rangeValues(min, step, max);
    }

    private Double getMean(final List<Double> list) {
        double mean = 0;
        for (Double d: list) {
            mean += d;
        }
        return list.isEmpty() ? 0.0 : mean / list.size();
    }

    @POST
    @Path("/evtset_comp")
    @Produces(MediaType.APPLICATION_JSON)
    public String evtset_comp(
        @Context final HttpServletRequest request,
        @FormParam("apikey") final String apikey,
        @FormParam("name") @DefaultValue("Custom Event Set") final String name,
        @FormParam("lon") final Double lon,
        @FormParam("lat") final Double lat,
        @FormParam("mag") final String mag_list,
        @FormParam("mag_min") final Double mag_min,
        @FormParam("mag_step") final Double mag_step,
        @FormParam("mag_max") final Double mag_max,
        @FormParam("depth") final String depth_list,
        @FormParam("depth_min") final Double depth_min,
        @FormParam("depth_step") final Double depth_step,
        @FormParam("depth_max") final Double depth_max,
        @FormParam("dip") final String dip_list,
        @FormParam("dip_min") final Double dip_min,
        @FormParam("dip_step") final Double dip_step,
        @FormParam("dip_max") final Double dip_max,
        @FormParam("strike") final String strike_list,
        @FormParam("strike_min") final Double strike_min,
        @FormParam("strike_step") final Double strike_step,
        @FormParam("strike_max") final Double strike_max,
        @FormParam("rake") final String rake_list,
        @FormParam("rake_min") final Double rake_min,
        @FormParam("rake_step") final Double rake_step,
        @FormParam("rake_max") final Double rake_max,
        @FormParam("dur") @DefaultValue("180") final Integer dur,
        @CookieParam("server_cookie") final String session
    ) {
        final int maxValue = Integer.MAX_VALUE;

        /* Authenticate user. */
        User user = auth(apikey, session);
        if (user == null) {
            return jsdenied();
        }

        if (!checkPerm(user, "evtset")) {
            return jsdenied();
        }

        if (lon == null || lat == null) {
            return jsfailure("Missing parameters.");
        }

        List<Double> mags = getValues(mag_list, mag_min, mag_step, mag_max);
        List<Double> depths = getValues(
            depth_list, depth_min, depth_step, depth_max
        );
        List<Double> dips = getValues(dip_list, dip_min, dip_step, dip_max);
        List<Double> strikes = getValues(
            strike_list, strike_min, strike_step, strike_max
        );
        List<Double> rakes = getValues(
            rake_list, rake_min, rake_step, rake_max
        );
        if (mags == null || depths == null || dips == null
            || strikes == null || rakes == null) {
            return jsfailure("Invalid range given.");
        }

        int count = mags.size() * depths.size() * dips.size()
            * strikes.size() * rakes.size();
        if (count > maxValue) {
            return jsfailure(
                "Too many combinations. At most " + maxValue + " are allowed."
            );
        }

        String setid = newRandomId(user.name);
        EventSet evtset = new EventSet(
            setid, count, count * (dur + totelDurationExtra)
        );
        Date date = new Date();
        List<String> evtids = new ArrayList<String>();
        int i = 0;
        for (Double mag: mags) {
            for (Double depth: depths) {
                for (Double dip: dips) {
                    for (Double strike: strikes) {
                        for (Double rake: rakes) {
                            EQParameter eqp = new EQParameter(
                                lon, lat, mag, depth, dip, strike, rake, date
                            );
                            String retid = computeEq(
                                eqp, user, name + " " + i, null, null, dur,
                                evtset, "easywave", defaultGridRes
                            );
                            evtids.add(retid);
                            i++;
                        }
                    }
                }
            }
        }
        BasicDBObject prop = new BasicDBObject("latitude", lat);
        prop.append("longitude", lon);
        prop.append("magnitude", getMean(mags));
        prop.append("mag_list", mags);
        prop.append("mag_min", mag_min);
        prop.append("mag_step", mag_step);
        prop.append("mag_max", mag_max);
        prop.append("depth_list", depths);
        prop.append("depth_min", depth_min);
        prop.append("depth_step", depth_step);
        prop.append("depth_max", depth_max);
        prop.append("dip_list", dips);
        prop.append("dip_min", dip_min);
        prop.append("dip_step", dip_step);
        prop.append("dip_max", dip_max);
        prop.append("strike_list", strikes);
        prop.append("strike_min", strike_min);
        prop.append("strike_step", strike_step);
        prop.append("strike_max", strike_max);
        prop.append("rake_list", rakes);
        prop.append("rake_min", rake_min);
        prop.append("rake_step", rake_step);
        prop.append("rake_max", rake_max);
        BasicDBObject set = new BasicDBObject("_id", setid);
        set.append("evtids", evtids);
        set.append("timestamp", date);
        set.append("name", name);
        set.append("duration", dur);
        set.append("prop", prop);
        set.append("user", user.objId);
        set.append("progress", 0.0);
        db.getCollection("evtsets").insert(set);

        /* create a new event */
        BasicDBObject event = new BasicDBObject();
        event.put("id", setid);
        event.put("user", user.objId);
        event.put("timestamp", new Date());
        event.put("event", "new_evtset");
        db.getCollection("events").insert(event);

        return jssuccess(
            new BasicDBObject("setid", setid).append("evtids", evtids)
        );
    }

    @POST
    @Path("/evtset_abort")
    @Produces(MediaType.APPLICATION_JSON)
    public String evtset_abort(
        @Context final HttpServletRequest request,
        @FormParam("apikey") final String apikey,
        @FormParam("setid") final String setid
    ) {
        User user = authApi(apikey);
        if (user == null) {
            return jsdenied();
        }
        DBObject query = new BasicDBObject("_id", setid)
            .append("user", user.objId);
        DBObject evtset = db.getCollection("evtsets").findOne(query);
        if (evtset == null) {
            return jsfailure("Event-Set not found.");
        }

        /* Get events and abort one task after the other. */
        boolean aborted = false;
        BasicDBList evtids = (BasicDBList) evtset.get("evtids");
        for (Object evtid: evtids) {
            aborted |= abortEvent((String) evtid);
        }
        if (!aborted) {
            return jsfailure("Computation cannot be aborted.");
        }

        /* Update earthquake in database. */
        DBObject set = new BasicDBObject(
            "$set", new BasicDBObject("abort", true)
        );
        db.getCollection("evtsets").update(
            new BasicDBObject("_id", setid), set
        );

        /* Create a new event. */
        BasicDBObject event = new BasicDBObject();
        event.put("id", setid);
        event.put("user", user.objId);
        event.put("timestamp", new Date());
        event.put("event", "abort");
        db.getCollection("events").insert(event);
        return jssuccess();
    }

    @POST
    @Path("/evt_abort")
    @Produces(MediaType.APPLICATION_JSON)
    public String evt_abort(
        @Context final HttpServletRequest request,
        @FormParam("apikey") final String apikey,
        @FormParam("evtid") final String evtid
    ) {
        User user = authApi(apikey);
        if (user == null) {
            return jsdenied();
        }
        DBObject query = new BasicDBObject("_id", evtid)
            .append("user", user.objId);
        DBObject evt = db.getCollection("eqs").findOne(query);
        if (evt == null) {
            return jsfailure("Event not found.");
        }
        if (!abortEvent(evtid)) {
            return jsfailure("Computation cannot be aborted.");
        }

        /* Update earthquake in database. */
        DBObject set = new BasicDBObject(
            "$set", new BasicDBObject("abort", true)
        );
        db.getCollection("eqs").update(new BasicDBObject("_id", evtid), set);

        /* Create a new event. */
        BasicDBObject event = new BasicDBObject();
        event.put("id", evtid);
        event.put("user", user.objId);
        event.put("timestamp", new Date());
        event.put("event", "abort");
        db.getCollection("events").insert(event);
        return jssuccess();
    }

    private boolean abortEvent(final String evtid) {
        Task task = scheduler.getTask(evtid);
        if (task == null) {
            return false;
        }
        return task.markAsAbort();
    }

    private String computeEq(
        final EQParameter eqp,
        final User user,
        final String name,
        final String parent,
        final String root,
        final Integer dur,
        final EventSet evtSet,
        final String algo,
        final Integer gridres
    ) {
        /* create a unique ID that is not already present in the DB */
        String id = newRandomId(user.name);

        DBObject parentObj = db.getCollection("eqs").findOne(
            new BasicDBObject("_id", parent)
        );
        int accel = 1;
        if (parentObj != null) {
            Integer val = (Integer) parentObj.get("accel");
            if (val != null) {
                accel = val;
            }
        }

        /* get current timestamp */
        Date timestamp = new Date();

        /* create new sub object that stores the properties */
        BasicDBObject sub = new BasicDBObject();
        sub.put("date", eqp.date);
        sub.put("region", name);
        sub.put("latitude", eqp.lat);
        sub.put("longitude", eqp.lon);
        sub.put("magnitude", eqp.mw);
        sub.put("slip", eqp.slip);
        sub.put("length", eqp.length);
        sub.put("width", eqp.width);
        sub.put("depth", eqp.depth);
        sub.put("dip", eqp.dip);
        sub.put("strike", eqp.strike);
        sub.put("rake", eqp.rake);

        /* create new DB object that should be added to the earthquake
         * collection */
        BasicDBObject obj = new BasicDBObject();
        obj.put("_id", id);
        obj.put("id", id);
        obj.put("user", user.objId);
        obj.put("timestamp", timestamp);
        obj.put("process", new ArrayList<>());
        obj.put("prop", sub);
        obj.put("root", root);
        obj.put("parent", parent);
        obj.put("accel", accel);

        if (evtSet != null) {
            obj.put("evtset", evtSet.setid);
        }

        /* insert object into collection */
        db.getCollection("eqs").insert(obj);

        /* create a new event */
        BasicDBObject event = new BasicDBObject();
        event.put("id", id);
        event.put("user", user.objId);
        event.put("timestamp", timestamp);
        event.put("event", "new");

        /* insert new event into 'events'-collection */
        db.getCollection("events").insert(event);

        /* start request */
        EQTask task = new EQTask(eqp, id, user, dur, accel);
        task.evtset = evtSet;
        task.algo = algo;
        task.gridres = gridres;
        if (evtSet == null) {
            if (algo.equals("hysea")) {
                task.setSlots(IScheduler.SLOT_HYSEA);
            } else {
                task.setSlots(
                    IScheduler.SLOT_NORMAL,
                    IScheduler.SLOT_EXCLUSIVE
                );
            }
        } else {
            task.setSlots(IScheduler.SLOT_NORMAL);
        }
        return request(id, task);
    }

    @POST
    @Path("/compute")
    @Produces(MediaType.APPLICATION_JSON)
    public String compute(
        @Context final HttpServletRequest request,
        @FormParam("name") @DefaultValue("Custom") final String name,
        @FormParam("lon") final Double lon,
        @FormParam("lat") final Double lat,
        @FormParam("mag") final Double mag,
        @FormParam("slip") final Double slip,
        @FormParam("length") final Double length,
        @FormParam("width") final Double width,
        @FormParam("depth") final Double depth,
        @FormParam("dip") final Double dip,
        @FormParam("strike") final Double strike,
        @FormParam("rake") final Double rake,
        @FormParam("dur") final Integer dur,
        @FormParam("date") final String dateStr,
        @FormParam("root") final String root,
        @FormParam("parent") final String parent,
        @FormParam("algo") @DefaultValue("easywave") final String algo,
        @FormParam("gridres") @DefaultValue("120") final Integer gridres,
        @CookieParam("server_cookie") final String session
    ) {

        Object[] required1 = {
            name, lon, lat, depth, dip, strike, rake, dur, algo, gridres
        };

        if (!checkParams(request, required1)) {
            return jsfailure();
        }

        Object[] required2 = {slip, length, width};
        if (!checkParams(request, required2)
            && (mag == null || !algo.equals("easywave"))) {
            return jsfailure();
        }

        /* only privileged users are allowed to compute own scenarios -
         * check for valid session */
        if (session == null) {
            return jsdenied();
        }

        /* check if session is valid and if the user is logged in */
        User user = signedIn(session);

        if (user == null) {
            return jsdenied();
        }

        Date date;
        if (dateStr != null && !dateStr.equals("")) {
            /* get Date object from date string */
            date = parseIsoDate(dateStr);
        } else {
            /* get current timestamp */
            date = new Date();
        }

        if (date == null) {
            return jsfailure();
        }

        System.out.println(new Date() + ": User " + user.name
            + " requested a computation of " + dur + " minutes.");

        /* upon here, we assume an authorized user */
        String compRoot = root;
        if (root != null && root.equals("")) {
            compRoot = null;
        }

        String compParent = parent;
        if (parent != null && parent.equals("")) {
            compParent = null;
        }

        EQParameter eqp;

        if (mag == null) {
            eqp = new EQParameter(
                lon, lat, slip, length, width, depth, dip, strike, rake, date
            );
        } else {
            eqp = new EQParameter(
                lon, lat, mag, depth, dip, strike, rake, date
            );
        }
        String retId = computeEq(
            eqp, user, name, compParent, compRoot, dur, null, algo, gridres
        );
        return jssuccess(new BasicDBObject("_id", retId));
    }

    private DBObject authApi(final String key, final String kind) {
        if (key == null) {
            return null;
        }
        BasicDBObject query = new BasicDBObject("api.key", key)
            .append("api.enabled", true);
        DBObject inst = db.getCollection("institutions").findOne(query);
        DBObject user = db.getCollection("users").findOne(
            query.append("permissions.api", true)
        );
        if (user != null && (kind == null || kind.equals("user"))) {
            return user;
        }
        if (inst != null && (kind == null || kind.equals("inst"))) {
            return inst;
        }
        return null;
    }

    private User authApi(final String key) {
        DBObject dbUser = authApi(key, "user");
        DBObject dbInst = authApi(key, "inst");
        User user = null;
        if (dbUser != null) {
            user = new User(dbUser, getInst(dbUser));
        } else if (dbInst != null) {
            user = new Inst(dbInst);
        }
        return user;
    }

    private User auth(final String key, final String session) {
        User user = authApi(key);
        if (user != null) {
            return user;
        }
        user = signedIn(session);
        return user;
    }

    private String getInst(final DBObject userObj) {
        ObjectId instId = (ObjectId) userObj.get("inst");
        DBObject instObj = db.getCollection("institutions")
            .findOne(new BasicDBObject("_id", instId));
        if (instObj == null) {
            return null;
        }
        return (String) instObj.get("name");
    }

    @POST
    @Path("/data_insert")
    @Produces(MediaType.APPLICATION_JSON)
    public String data_insert(
        @Context final HttpServletRequest request,
        @FormParam("inst") final String inst,
        @FormParam("secret") final String secret,
        @FormParam("id") final String id,
        @FormParam("name") final String name,
        @FormParam("lon") final Double lon,
        @FormParam("lat") final Double lat,
        @FormParam("mag") final Double mag,
        @FormParam("slip") final Double slip,
        @FormParam("length") final Double length,
        @FormParam("width") final Double width,
        @FormParam("depth") final Double depth,
        @FormParam("dip") final Double dip,
        @FormParam("strike") final Double strike,
        @FormParam("rake") final Double rake,
        @FormParam("date") final String dateStr,
        @FormParam("sea_area") final String sea_area,
        @FormParam("root") final String root,
        @FormParam("parent") final String parent,
        @FormParam("comp") final Integer comp,
        @FormParam("accel") final Integer accel,
        @FormParam("gridres") final Integer gridres,
        @FormParam("apikey") final String apikey,
        @FormParam("algo") @DefaultValue("easywave") final String algo
    ) {
        String useApiKey = apikey;
        Integer useAccel = accel;
        String useRoot = root;

        /* Check for invalid parameter configurations. */
        if ((inst != null || secret != null) && useApiKey != null) {
            return jsfailure("Don't mix 'apikey' and 'secret'.");
        }

        if (mag != null && (slip != null || length != null || width != null)) {
            return jsfailure(
                "Don't mix 'mag' with 'slip', 'length' and 'width'."
            );
        }

        /* Support 'inst' and 'secret' for compatibility reasons. */
        if (inst != null && secret != null) {
            /* Obtain the 'apikey' and pretend a call to the new api. */
            DBObject query = new BasicDBObject("name", inst)
                .append("secret", secret);
            DBObject tmpInst = db.getCollection("institutions")
                .findOne(query);
            if (tmpInst == null) {
                return jsdenied();
            }
            useApiKey = (String) ((DBObject) tmpInst.get("api")).get("key");
            if (useApiKey == null) {
                return jsfailure("No 'apikey' set for this institution!");
            }
        }

        /* Continue with the new API. */
        Object[] required = {useApiKey, id, name, dateStr};

        if (!checkParams(request, required)) {
            return jsfailure();
        }

        DBObject dbUser = authApi(useApiKey, "user");
        DBObject dbInst = authApi(useApiKey, "inst");

        /* check if we got a valid institution and the correct secret */
        ObjectId userId;
        String username;
        User user;
        if (dbUser != null) {
            userId = (ObjectId) dbUser.get("_id");
            username = (String) dbUser.get("username");
            user = new User(dbUser, getInst(dbUser));
        } else if (dbInst != null) {
            userId = (ObjectId) dbInst.get("_id");
            username = (String) dbInst.get("name");
            user = new Inst(dbInst);
        } else {
            return jsdenied();
        }

        System.out.println(user.name + " - " + user.inst);

        /* get Date object from date string */
        System.out.println(dateStr);
        Date date = parseIsoDate(dateStr);
        if (date == null) {
            return jsfailure("Invalid date format.");
        }

        System.out.println(id);

        /* get current timestamp */
        Date timestamp = new Date();

        /* create new sub object that stores the properties */
        BasicDBObject sub = new BasicDBObject();
        sub.put("date", date);
        sub.put("region", name);
        sub.put("latitude", lat);
        sub.put("longitude", lon);
        sub.put("magnitude", mag);
        sub.put("slip", slip);
        sub.put("length", length);
        sub.put("width", width);
        sub.put("depth", depth);
        sub.put("dip", dip);
        sub.put("strike", strike);
        sub.put("rake", rake);
        sub.put("sea_area", sea_area);

        if (useAccel == null) {
            useAccel = 1;
        }

        /* create new DB object that should be added to the eqs collection */
        BasicDBObject obj = new BasicDBObject();
        obj.put("id", id);
        obj.put("user", userId);
        obj.put("timestamp", timestamp);
        obj.put("prop", sub);
        obj.put("root", useRoot);
        obj.put("parent", parent);
        obj.put("accel", useAccel);
        // obj.put( "gridres", gridres );

        /* create a new event */
        BasicDBObject event = new BasicDBObject();
        event.put("user", userId);
        event.put("timestamp", timestamp);
        event.put("event", "new");

        Long refineId = 0L;

        /* get earthquake collection */
        DBCollection coll = db.getCollection("eqs");
        /* search for given id */
        BasicDBObject inQuery = new BasicDBObject("id", id)
            .append("user", userId);
        DBCursor cursor = coll.find(inQuery)
            .sort(new BasicDBObject("refineId", -1));

        BasicDBObject entry = null;

        /* if id is already used, make a refinement */
        if (cursor.hasNext()) {

            /* get properties of returned entry */
            entry = (BasicDBObject) cursor.next();

            /* update entry ID in database by appending deprecated field */
            BasicDBObject depr = new BasicDBObject("depr", true);
            coll.update(entry, new BasicDBObject("$set", depr));

            refineId = (Long) entry.get("refineId");

            if (refineId == null) {
                refineId = new Long(0);
            }

            refineId++;

            /* override parent and root attributes */
            useRoot = entry.get("root") == null
                ? (String) entry.get("_id")
                : (String) entry.get("root");
            obj.put("root", useRoot);
            obj.put("parent", entry.get("_id"));

            /* override event type */
            event.put("event", "update");
        }

        /* set refinement and compound Ids */
        final CompId compId = new CompId(username, id, refineId);
        obj.put("_id", compId.toString());
        obj.put("refineId", refineId);
        event.put("id", compId.toString());

        /* clean up query */
        cursor.close();

        /* insert object into 'eqs' collection */
        coll.insert(obj);

        System.out.println(obj);

        Object[] reqComp1 = {id, lon, lat, mag, depth, dip, strike, rake};
        Object[] reqComp2 = {
            id, lon, lat, slip, length, width, depth, dip, strike, rake
        };
        boolean simulate = comp != null && (checkParams(request, reqComp1)
            || checkParams(request, reqComp2));

        /* insert new event into 'events'-collection */
        db.getCollection("events").insert(event);

        System.out.println(simulate);

        if (simulate) {
            // computeById(
            // request, null, null, id, refineId, comp, useAccel, useApiKey
            // );
            computeByIdForUser(
                user, compId.toString(), comp, useAccel, gridres, algo
            );
        } else {
            /* run request in a separate thread to avoid blocking */
            new Thread() {
                public void run() {
                    sendPost(
                        GlobalParameter.wsgi_url + "webguisrv/post_compute",
                        "evtid=" + compId.toString()
                    );
                }
            }.start();
        }

        return jssuccess(
            new BasicDBObject("refineId", refineId)
            .append("evtid", compId.toString())
        );
    }

    private Integer statusForEvtId(final String evtid, final Integer raw) {
        /* Check if event exists. */
        DBCollection eqs = db.getCollection("eqs");
        BasicDBObject query = new BasicDBObject("_id", evtid);
        DBObject event = eqs.findOne(query);
        if (event == null) {
            return null;
        }
        /* Check if computation has been initiated. */
        Integer p;
        if (raw > 0) {
            query.append("raw_progress", new BasicDBObject("$ne", null));
            event = eqs.findOne(query);
            if (event == null) {
                return statusNoComp;
            }
            p = ((BasicDBObject) event).getInt("raw_progress");
        } else {
            query.append("process", new BasicDBObject("$ne", null));
            if (eqs.findOne(query) == null) {
                return statusNoComp;
            }
            /* Check if computation was started successfully. */
            query.append("process", new BasicDBObject("$size", 1));
            if (eqs.findOne(query) == null) {
                return statusFailed;
            }
            /* Extract the progress from the database object with this clear
             * command ;) */
            p = ((BasicDBObject) ((BasicDBList) event.get("process"))
                .get(0)).getInt("progress");
        }
        return p;
    }

    private Integer evtsetStatusForId(final String setid) {
        /* Check if event-set exists. */
        DBCollection evtsets = db.getCollection("evtsets");
        BasicDBObject query = new BasicDBObject("_id", setid);
        DBObject evtset = evtsets.findOne(query);
        if (evtset == null) {
            return null;
        }
        if (evtset.get("abort") != null) {
            return statusAbort;
        }
        Double progress = (Double) getField(evtset, "progress");
        if (progress == null) {
            return statusNoComp;
        }
        return progress.intValue();
    }

    @POST
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public String status(
        @Context final HttpServletRequest request,
        @FormParam("apikey") final String apikey,
        @FormParam("evtid") final String evtid,
        @FormParam("raw") @DefaultValue("0") final Integer raw
    ) {
        /* Validate API-key. For now, it is possible to query foreign events. */
        if (authApi(apikey, null) == null) {
            return jsdenied();
        }
        Integer progress = statusForEvtId(evtid, raw);
        if (progress == null) {
            return jsfailure("Event not found.");
        }
        if (progress == statusNoComp) {
            return jssuccess(new BasicDBObject("comp", "none"));
        }
        if (progress == statusFailed) {
            return jssuccess(new BasicDBObject("comp", "failed"));
        }
        String comp = progress == progressFinished ? "success" : "pending";
        return jssuccess(
            new BasicDBObject("comp", comp)
            .append("progress", progress.toString())
        );
    }

    @POST
    @Path("/evtset_status")
    @Produces(MediaType.APPLICATION_JSON)
    public String evtset_status(
        @Context final HttpServletRequest request,
        @FormParam("apikey") final String apikey,
        @FormParam("setid") final String setid,
        @CookieParam("server_cookie") final String session
    ) {
        /* Validate API-key. For now, it is possible to query foreign events. */
        if (authApi(apikey, null) == null && signedIn(session) == null) {
            return jsdenied();
        }
        Integer progress = evtsetStatusForId(setid);
        if (progress == null) {
            return jsfailure("Event-set not found.");
        }
        if (progress == statusAbort) {
            return jssuccess(new BasicDBObject("comp", "abort"));
        }
        if (progress == statusNoComp) {
            return jssuccess(new BasicDBObject("comp", "none"));
        }
        String comp = progress == progressFinished ? "success" : "pending";
        BasicDBObject ret = new BasicDBObject("comp", comp)
            .append("progress", progress.toString());
        /* Append runtime information. */
        DBObject evtset = db.getCollection("evtsets")
            .findOne(new BasicDBObject("_id", setid));
        ret.append("calcTime", getField(evtset, "calcTime"));
        return jssuccess(ret);
    }

    @POST
    @Path("/signin")
    @Produces(MediaType.APPLICATION_JSON)
    public String signin(
        @Context final HttpServletRequest request,
        @Context final HttpServletResponse response,
        @FormParam("username") final String username,
        @FormParam("password") final String password
    ) {
        Cookie sessionCookie = new Cookie("server_cookie", "java!");
        sessionCookie.setPath("/");
        sessionCookie.setHttpOnly(true);
        // sessionCookie.setSecure( true );

        if (username == null || password == null
            || username.equals("") || password.equals("")) {
            return jsfailure();
        }

        DBCollection coll = db.getCollection("users");

        DBCursor cursor = coll.find(new BasicDBObject("username", username));
        DBObject obj;

        if (cursor.hasNext()) {

            obj = cursor.next();
            String hash = (String) obj.get("password");
            String session = (String) obj.get("session");

            MessageDigest sha256;

            try {
                sha256 = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                return "{ \"status\": \"error\" }";
            }

            if (
                hash.equals(
                    Base64.getEncoder().encodeToString(
                        sha256.digest(password.getBytes())
                    )
                )
            ) {
                if (session == null) {
                    session = getSessionKey();
                    obj.put("session", session);
                    coll.update(new BasicDBObject("username", username), obj);
                }

                sessionCookie.setValue(session);
                response.addCookie(sessionCookie);

                BasicDBObject result = new BasicDBObject("status", "success");
                result.put("user", getUserObj(username));

                BasicDBObject perm = (BasicDBObject) obj.get("permissions");
                if (perm != null && perm.getBoolean("nologin")) {
                    return jsdenied(new BasicDBObject("nologin", true));
                }

                System.out.println(
                    new Date() + " SignIn from user " + username
                );
                DBObject login = new BasicDBObject("date", new Date())
                    .append("user", username);
                db.getCollection("logins").insert(login);

                return gson.toJson(result);
            }
        }

        return jsfailure();
    }

    @POST
    @Path("/signout")
    @Produces(MediaType.APPLICATION_JSON)
    public String signout(
        @Context final HttpServletRequest request,
        @Context final HttpServletResponse response,
        @FormParam("username") final String username,
        @CookieParam("server_cookie") final String session
    ) {
        if (username == null || username.equals("") || session == null) {
            return jsfailure();
        }

        DBCollection coll = db.getCollection("users");

        DBCursor cursor = coll.find(new BasicDBObject("username", username));
        DBObject obj;

        if (cursor.hasNext()) {
            obj = cursor.next();

            if (session.equals((String) obj.get("session"))) {
                obj.put("session", null);
                coll.update(new BasicDBObject("username", username), obj);

                Cookie sessionCookie = new Cookie("server_cookie", "");
                sessionCookie.setPath("/");
                sessionCookie.setMaxAge(0);
                response.addCookie(sessionCookie);

                System.out.println(
                    new Date() + " SignOut from user " + username
                );
                return jssuccess();
            }
        }

        return jsfailure();
    }

    @POST
    @Path("/session")
    @Produces(MediaType.APPLICATION_JSON)
    public String session(
        @Context final HttpServletRequest request,
        @Context final HttpServletResponse response,
        @CookieParam("server_cookie") final String session
    ) {
        if (session == null) {
            return jsfailure();
        }

        User user = signedIn(session);

        if (user != null) {
            DBObject userObj = getUserObj(user.name);
            BasicDBObject result = new BasicDBObject("status", "success");
            result.put("user", userObj);

            BasicDBObject perm = (BasicDBObject) userObj.get("permissions");
            if (perm != null && perm.getBoolean("nologin")) {
                return jsdenied(new BasicDBObject("nologin", true));
            }

            System.out.println(
                new Date() + " Resuming session for user " + user.name
            );

            return gson.toJson(result);
        }

        return jsfailure();
    }

    private DBObject getUserObj(final String username) {
        DBCollection coll = db.getCollection("users");

        DBCursor cursor = coll.find(new BasicDBObject("username", username));

        if (!cursor.hasNext()) {
            return null;
        }

        DBObject obj = cursor.next();
        cursor.close();

        BasicDBObject userObj = new BasicDBObject(
            "username", obj.get("username")
        );
        userObj.put("_id", obj.get("_id"));
        userObj.put("permissions", obj.get("permissions"));
        userObj.put("properties", obj.get("properties"));
        userObj.put("notify", obj.get("notify"));
        userObj.put("api", obj.get("api"));

        ObjectId instId = (ObjectId) obj.get("inst");

        cursor = db.getCollection("institutions")
            .find(new BasicDBObject("_id", instId));

        String instName = null;

        if (cursor.hasNext()) {
            DBObject inst = cursor.next();
            inst.removeField("_id");
            inst.removeField("secret");
            userObj.put("inst", inst);
            instName = (String) inst.get("name");
        }

        cursor.close();

        if (instName == null || instName.equals("gfz")
            || instName.equals("tdss15")) {
            instName = "gfz_ex_test";
        }

        /* get all available country codes and count elements in each group */
        DBObject groupFields = new BasicDBObject("_id", "$country");
        groupFields.put("count", new BasicDBObject("$sum", 1));

        DBObject group = new BasicDBObject("$group", groupFields);

        BasicDBList types = new BasicDBList();
        types.add(new BasicDBObject("sensor", "rad"));
        types.add(new BasicDBObject("sensor", "prs"));
        types.add(new BasicDBObject("sensor", "pr1"));
        types.add(new BasicDBObject("sensor", "flt"));
        types.add(new BasicDBObject("sensor", null));

        DBObject filterFields = new BasicDBObject("$or", types);

        BasicDBList andList = new BasicDBList();
        andList.add(filterFields);
        andList.add(new BasicDBObject("inst", instName));

        DBObject andObj = new BasicDBObject("$and", andList);
        DBObject filter = new BasicDBObject("$match", andObj);

        /* sort alphabetically */
        DBObject sortFields = new BasicDBObject("_id", 1);
        DBObject sort = new BasicDBObject("$sort", sortFields);

        AggregationOutput output = db.getCollection("stations")
            .aggregate(filter, group, sort);
        BasicDBList countries = new BasicDBList();

        /* convert answer into string list */
        @SuppressWarnings("unchecked")
        List<String> clist = (List<String>) obj.get("countries");

        for (DBObject res : output.results()) {
            String code = (String) res.get("_id");
            if (code == null) {
                continue;
            }
            boolean isOn = (clist != null) && clist.contains(code);
            res.put("on", isOn);
            countries.add(res);
        }

        userObj.put("countries", countries);

        return userObj;
    }

    private User signedIn(final String session) {
        if (session == null) {
            return null;
        }

        DBCollection coll = db.getCollection("users");
        DBCollection insts = db.getCollection("institutions");

        DBCursor cursor = coll.find(new BasicDBObject("session", session));
        DBObject obj;
        DBObject inst = null;

        if (cursor.hasNext()) {
            /* we have found a valid session key */
            obj = cursor.next();
            cursor.close();

            cursor = insts.find(new BasicDBObject("_id", obj.get("inst")));
            if (cursor.hasNext()) {
                inst = cursor.next();
            }

            return new User(obj, inst);
        }

        return null;
    }

    public String getSessionKey() {
        return UUID.randomUUID().toString();
    }

    @POST
    @Path("/fetch")
    @Produces(MediaType.APPLICATION_JSON)
    public String fetch(
        @Context final HttpServletRequest request,
        @FormParam("limit") @DefaultValue("0") final int limit,
        @FormParam("delay") @DefaultValue("0") final int delay,
        @FormParam("undersea") @DefaultValue("false") final boolean undersea,
        @CookieParam("server_cookie") final String session
    ) {
        /* check session key and find out if the request comes from an
         * authorized user */
        User user = signedIn(session); // returns null if user is not logged in

        /* create lists for general and user specific earthquake entries */
        ArrayList<DBObject> mlist = new ArrayList<DBObject>();
        ArrayList<DBObject> ulist = new ArrayList<DBObject>();

        /* we want all entries since the beginning of time */
        Date maxTimestamp = new Date();

        /* used to convert to desired time format used by MongoDB */
        SimpleDateFormat sdf = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        );
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

        /* select collection which contain the earthquake entries */
        DBCollection coll = db.getCollection("eqs");

        // ArrayList<User> users = new ArrayList<User>(institutions.values());
        ArrayList<User> users = new ArrayList<User>();
        users.add(user);

        if (user != null) {
            if (user.inst != null) {
                users.add(institutions.get(user.inst));
            } else {
                users.add(institutions.get("gfz"));
            }

            DBCursor csr = db.getCollection("users").find(
                new BasicDBObject("username", user.name)
                    .append("provider", new BasicDBObject("$ne", null))
            );
            if (csr.hasNext()) {
                for (Object p : (BasicDBList) csr.next().get("provider")) {
                    DBObject inst = db.getCollection("institutions")
                        .findOne(new BasicDBObject("_id", p));
                    if (inst != null) {
                        users.add(institutions.get(inst.get("name")));
                    }
                }
            }
        }

        /* return only entries that are older than 'delay' minutes */
        Date upperTimeLimit = new Date(
            System.currentTimeMillis() - delay * convertToSeconds
                * convertToMillis
        );

        /* get earthquakes for each of the given users */
        for (User curUser : users) {
            if (curUser == null) {
                continue;
            }

            /* create DB query */
            BasicDBObject inQuery = new BasicDBObject("user", curUser.objId);

            if (undersea) {
                inQuery.append("prop.sea_area", new BasicDBObject("$ne", null));
            }

            if (delay > 0) {
                inQuery.append(
                    "prop.date", new BasicDBObject("$lt", upperTimeLimit)
                );
            }

            inQuery.append("depr", new BasicDBObject("$ne", true));
            inQuery.append("evtset", null);

            /* query DB, sort the results by date and limit the number of
             * returned entries */
            DBCursor cursor = coll.find(inQuery)
                .sort(new BasicDBObject("prop.date", -1));

            if (limit > 0) {
                cursor = cursor.limit(limit);
            }

            /* walk through the returned entries */
            for (DBObject obj : cursor) {
                obj.removeField("image");

                /* check if entry belongs to general or user specific list */
                if (user != null && obj.get("user").equals(user.objId)) {
                    ulist.add(obj);
                } else {
                    mlist.add(obj);
                }

                /* update timestamp */
                Date timestamp = (Date) obj.get("timestamp");
                if (timestamp.after(maxTimestamp)) {
                    maxTimestamp = timestamp;
                }
            }

            /* clean up query */
            cursor.close();
        }

        /* create new JSON object that can be used directly within JavaScript */
        JsonObject jsonObj = new JsonObject();
        jsonObj.add("main", gson.toJsonTree(mlist));
        jsonObj.add("user", gson.toJsonTree(ulist));

        if (user != null) {
            List<DBObject> msglist = msg(limit, user);

            if (!msglist.isEmpty()) {
                Date timestamp = (Date) msglist.get(0).get("CreatedTime");
                if (timestamp.after(maxTimestamp)) {
                    maxTimestamp = timestamp;
                }
            }

            jsonObj.add("msg", gson.toJsonTree(msglist));
        } else {
            jsonObj.add("msg", gson.toJsonTree(new ArrayList<DBObject>()));
        }

        List<DBObject> evtsets = new ArrayList<DBObject>();
        if (user != null) {
            BasicDBObject query = new BasicDBObject("user", user.objId);
            query.append("timestamp", new BasicDBObject("$lte", maxTimestamp));
            DBCursor cursor = db.getCollection("evtsets").find(query)
                .sort(new BasicDBObject("timestamp", -1))
                .limit(findEvtsetLimit);
            evtsets = cursor.toArray();
        }
        jsonObj.add("evtsets", gson.toJsonTree(evtsets));

        /* TODO */
        if (user != null) {
            for (
                Map.Entry<String, IDataProvider> entry : providers.entrySet()
            ) {
                List<DBObject> list = entry.getValue()
                    .fetch(user, maxTimestamp, limit);
                jsonObj.add(entry.getKey(), gson.toJsonTree(list));
            }
        }

        jsonObj.addProperty("ts", sdf.format(maxTimestamp));
        return jsonObj.toString();
    }

    @POST
    @Path("/update")
    @Produces(MediaType.APPLICATION_JSON)
    public String update(
        @Context final HttpServletRequest request,
        @FormParam("ts") final String ts,
        @FormParam("delay") @DefaultValue("0") final int delay,
        @CookieParam("server_cookie") final String session
    ) {
        /* check session key and find out if the request comes from an
         * authorized user */
        User user = signedIn(session);

        /* create lists for general and user specific earthquake entries */
        ArrayList<DBObject> mlist = new ArrayList<DBObject>();
        ArrayList<DBObject> ulist = new ArrayList<DBObject>();
        ArrayList<DBObject> evtsets = new ArrayList<DBObject>();

        /* used to convert to desired time format used by MongoDB */
        SimpleDateFormat sdf = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        );
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

        /* convert timestamp from String to Date; return on error */
        Date timestamp;

        try {
            timestamp = sdf.parse(ts);
        } catch (ParseException e) {
            e.printStackTrace();
            return null;
        }

        /* select collection which contain the events */
        DBCollection coll = db.getCollection("events");

        /* create list of DB objects that contains all desired users */
        BasicDBList users = new BasicDBList();

        // for( User curUser: institutions.values() )
        // users.add( new BasicDBObject( "user", curUser.objId ) );

        if (user != null) {

            users.add(new BasicDBObject("user", user.objId));

            if (user.inst != null) {
                users.add(
                    new BasicDBObject("user", institutions.get(user.inst).objId)
                );
            } else {
                users.add(
                    new BasicDBObject("user", institutions.get("gfz").objId)
                );
            }

            DBCursor csr = db.getCollection("users")
                .find(
                    new BasicDBObject("username", user.name)
                    .append("provider", new BasicDBObject("$ne", null))
                );
            if (csr.hasNext()) {
                for (Object p : (BasicDBList) csr.next().get("provider")) {
                    users.add(new BasicDBObject("user", p));
                }
            }
        }

        /* return only entries that are older than 'delay' minutes */
        Date upperTimeLimit = new Date(
            System.currentTimeMillis() - delay * convertToSeconds
                * convertToMillis
        );

        /* create DB query - search for newer events related to the general
         * list or the user */
        BasicDBList time = new BasicDBList();
        time.add(
            new BasicDBObject("timestamp", new BasicDBObject("$gt", timestamp))
        );
        BasicDBObject inQuery = new BasicDBObject("$and", time);
        inQuery.put("$or", users);

        boolean first = true;

        Map<String, List<DBObject>> lists =
            new HashMap<String, List<DBObject>>();
        for (Map.Entry<String, IDataProvider> entry : providers.entrySet()) {
            lists.put(entry.getKey(), new ArrayList<DBObject>());
        }

        /* walk through the returned entries */
        if (user != null) {
            /* query DB, sort the results by timestamp */
            DBCursor cursor = coll.find(inQuery)
                .sort(new BasicDBObject("timestamp", -1));

            for (DBObject obj : cursor) {
                if (first) {
                    timestamp = (Date) obj.get("timestamp");
                    first = false;
                }

                /* get corresponding entry from earthquake collection */
                String id = (String) obj.get("id");

                BasicDBObject objQuery = new BasicDBObject();
                objQuery.put("olduser", new BasicDBObject("$exists", false));

                if (delay > 0) {
                    objQuery.put(
                        "prop.date",
                        new BasicDBObject("$lt", upperTimeLimit)
                    );
                }

                DBObject obj2 = null;

                if (obj.get("event").equals("msg_sent")) {
                    objQuery.put("Message-ID", id);
                    obj2 = db.getCollection("messages_sent").findOne(objQuery);
                } else if (obj.get("event").equals("msg_recv")) {
                    objQuery.put("Message-ID", id);
                    obj2 = db.getCollection("messages_received")
                        .findOne(objQuery);
                } else if (obj.get("event").equals("new_evtset")) {
                    objQuery.put("_id", id);
                    obj2 = db.getCollection("evtsets").findOne(objQuery);
                } else {
                    objQuery.put("_id", id);
                    obj2 = db.getCollection("eqs").findOne(objQuery);
                    if (obj2 == null) {
                        obj2 = db.getCollection("evtsets").findOne(objQuery);
                    }
                }

                for (
                    Map.Entry<String, IDataProvider> entry
                    : providers.entrySet()
                ) {
                    entry.getValue().add(lists.get(entry.getKey()), obj);
                }

                /*  */
                if (obj2 != null) {
                    /* add event type to entry */
                    String event = (String) obj.get("event");
                    obj2.put("event", event);

                    if (obj.get("event").equals("msg_recv")) {
                        obj2.put("Dir", "in");
                        obj2.put("To", new String[] {user.name});

                        DBCursor csrUser = db.getCollection("users")
                            .find(
                                new BasicDBObject("_id", obj2.get("SenderID"))
                            );
                        if (csrUser.hasNext()) {
                            obj2.put(
                                "From", (String) csrUser.next().get("username")
                            );
                        }

                        DBCursor csrParent = db.getCollection("eqs")
                            .find(
                                new BasicDBObject("_id", obj2.get("ParentId"))
                            );

                        if (csrParent.hasNext()) {
                            obj2.put("parentEvt", csrParent.next());
                        }
                    }

                    if (event.equals("new_evtset")) {
                        evtsets.add(obj2);
                    } else {
                        /* check if entry belongs to general or user specific
                         * list */
                        if (user != null
                            && obj.get("user").equals(user.objId)) {
                            ulist.add(obj2);
                        } else {
                            mlist.add(obj2);
                        }
                    }

                    /* update timestamp */
                    /*
                     * TODO: this is just a temporary solution, because
                     *       progress events could be
                     * delivered multiple times
                     */
                    if (delay <= 0 || event.equals("new")) {
                        if (first) {
                            timestamp = (Date) obj.get("timestamp");
                            first = false;
                        }
                    }
                }
            }

            /* clean up query */
            cursor.close();
        }

        /* create new JSON object that can be used directly within JavaScript */
        JsonObject jsonObj = new JsonObject();
        jsonObj.addProperty("serverTime", sdf.format(new Date()));
        jsonObj.addProperty("ts", sdf.format(timestamp));
        jsonObj.add("main", gson.toJsonTree(mlist));
        jsonObj.add("user", gson.toJsonTree(ulist));
        jsonObj.add("evtsets", gson.toJsonTree(evtsets));

        for (Map.Entry<String, IDataProvider> entry : providers.entrySet()) {
            jsonObj.add(
                entry.getKey(), gson.toJsonTree(lists.get(entry.getKey()))
            );
        }

        return jsonObj.toString();
    }

    @POST
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    public String search(
        @Context final HttpServletRequest request,
        @FormParam("text") final String text,
        @CookieParam("server_cookie") final String session
    ) {
        /* check session key and find out if the request comes from an
         * authorized user */
        User user = signedIn(session);

        /* create list of DB objects that contains all desired users */
        BasicDBList users = new BasicDBList();

        for (User curUser : institutions.values()) {
            users.add(new BasicDBObject("user", curUser.objId));
        }

        if (user != null) {
            users.add(new BasicDBObject("user", user.objId));
        }

        DBCollection coll = db.getCollection("eqs");
        DBCollection msgColl = db.getCollection("messages_sent");
        DBCollection recvColl = db.getCollection("messages_received");
        DBCollection evtsetColl = db.getCollection("evtsets");

        List<DBObject> refinements = coll.find(new BasicDBObject("id", text))
            .toArray();

        BasicDBList list = new BasicDBList();
        list.add(new BasicDBObject("_id", text));
        list.add(new BasicDBObject("id", text));
        list.add(new BasicDBObject("root", text));
        list.add(new BasicDBObject("parent", text));

        for (DBObject obj : refinements) {
            String compId = (String) obj.get("_id");
            list.add(new BasicDBObject("root", compId));
            list.add(new BasicDBObject("parent", compId));
        }

        BasicDBList and = new BasicDBList();
        and.add(new BasicDBObject("$or", list));
        and.add(new BasicDBObject("$or", users));

        BasicDBObject inQuery = new BasicDBObject("$and", and);

        BasicDBObject sort = new BasicDBObject("timestamp", -1);
        sort.put("prop.date", -1);
        DBCursor cursor = coll.find(inQuery).sort(sort);

        List<DBObject> results = new ArrayList<DBObject>();
        results.addAll(cursor.toArray());

        cursor.close();

        /* TODO: generalize field names */
        list = new BasicDBList();
        list.add(new BasicDBObject("EventID", text));
        list.add(new BasicDBObject("ParentId", text));

        for (DBObject obj : refinements) {
            String compId = (String) obj.get("_id");
            list.add(new BasicDBObject("EventID", compId));
            list.add(new BasicDBObject("ParentId", compId));
        }

        and = new BasicDBList();
        and.add(new BasicDBObject("$or", list));
        and.add(new BasicDBObject("SenderID", user.objId));

        inQuery = new BasicDBObject("$and", and);

        cursor = msgColl.find(inQuery)
            .sort(new BasicDBObject("CreatedTime", -1));

        for (DBObject obj : cursor) {
            obj.put("kind", "msg");
            obj.put("Dir", "out");
            results.add(obj);
        }

        cursor.close();

        and = new BasicDBList();
        and.add(new BasicDBObject("$or", list));
        and.add(new BasicDBObject("ReceiverID", user.objId));

        inQuery = new BasicDBObject("$and", and);

        cursor = recvColl.find(inQuery)
            .sort(new BasicDBObject("CreatedTime", -1));

        for (DBObject obj : cursor) {
            obj.put("kind", "msg");
            obj.put("Dir", "in");
            results.add(obj);
        }

        cursor.close();

        DBObject evtset = evtsetColl.findOne(new BasicDBObject("_id", text));
        if (evtset != null) {
            List<DBObject> evts = coll.find(
                new BasicDBObject(
                    "id", new BasicDBObject("$in", evtset.get("evtids"))
                )
            ).toArray();
            results.addAll(evts);
        }

        /* returning only cursor.toArray().toString() makes problems with the
         * date fields */
        return gson.toJsonTree(results).toString();
    }

    @POST
    @Path("/delete")
    @Produces(MediaType.APPLICATION_JSON)
    public String delete(
        @Context final HttpServletRequest request,
        @FormParam("id") final String id,
        @FormParam("type") final String type,
        @CookieParam("server_cookie") final String session
    ) {
        Object[] required = {id};

        if (!checkParams(request, required)) {
            return jsfailure();
        }

        /* check session key and find out if the request comes from an
         * authorized user */
        User user = signedIn(session);

        if (user == null) {
            return jsdenied();
        }

        DBCollection coll;
        BasicDBObject inQuery = new BasicDBObject();
        BasicDBObject fields = new BasicDBObject();

        if (type != null && type.equals("msg_in")) {
            coll = db.getCollection("messages_received");
            inQuery.put("ReceiverID", user.objId);
            inQuery.put("Message-ID", id);

            fields.put("ReceiverID", null);
        } else if (type != null && type.equals("msg_out")) {
            coll = db.getCollection("messages_sent");
            inQuery.put("SenderID", user.objId);
            inQuery.put("Message-ID", id);

            fields.put("SenderID", null);
        } else {
            coll = db.getCollection("eqs");
            inQuery.put("user", user.objId);
            inQuery.put("_id", id);

            fields.put("user", null);
        }

        fields.put("olduser", user.objId);

        // int num = coll.remove( inQuery ).getN();
        BasicDBObject set = new BasicDBObject("$set", fields);
        System.out.println(set);
        int num = coll.update(inQuery, set).getN();

        if (num > 0) {
            return jssuccess();
        }

        return jsfailure();
    }

    @POST
    @Path("/staticLnk")
    @Produces(MediaType.APPLICATION_JSON)
    public String staticLnk(
        @Context final HttpServletRequest request,
        @FormParam("id") final String id,
        @FormParam("lon") final Double lon,
        @FormParam("lat") final Double lat,
        @FormParam("zoom") final Double zoom,
        @CookieParam("server_cookie") final String session
    ) {
        Object[] required = {id, lon, lat, zoom};

        if (!checkParams(request, required)) {
            return jsfailure();
        }

        /* check session key and find out if the request comes from an
         * authorized user */
        User user = signedIn(session);

        if (user == null) {
            return jsdenied();
        }

        /* TODO: check if this id exists and their usage is authorized */
        String key = staticInt(id, lon, lat, zoom, user.objId);

        BasicDBObject result = new BasicDBObject("key", key);

        return jssuccess(result);
    }

    private String staticInt(
        final String id,
        final Double lon,
        final Double lat,
        final Double zoom,
        final Object uid
    ) {
        DBCollection coll = db.getCollection("shared_links");
        BasicDBObject inQuery = new BasicDBObject("evtid", id);
        inQuery.put("lon", lon);
        inQuery.put("lat", lat);
        inQuery.put("zoom", zoom);
        inQuery.put("timestamp", new Date());
        inQuery.put("userid", uid);

        coll.insert(inQuery);
        ObjectId objId = (ObjectId) inQuery.get("_id");

        return objId.toString();
    }

    private String getIP(final HttpServletRequest request) {

        String ip = request.getHeader("X-FORWARDED-FOR");
        if (ip == null) {
            ip = request.getRemoteAddr();
        }

        return ip;
    }

    @POST
    @Path("/getShared")
    @Produces(MediaType.APPLICATION_JSON)
    public String getShared(
        @Context final HttpServletRequest request,
        @FormParam("lnkid") final String lnkId,
        @CookieParam("server_cookie") final String session
    ) {
        Object[] required = {lnkId};

        if (!checkParams(request, required)) {
            return jsfailure();
        }

        ObjectId objId;

        try {
            objId = new ObjectId(lnkId);
        } catch (IllegalArgumentException e) {
            return jsfailure();
        }

        DBCollection coll = db.getCollection("shared_links");
        BasicDBObject inQuery = new BasicDBObject("_id", objId);

        DBCursor cursor = coll.find(inQuery);

        if (!cursor.hasNext()) {
            return jsfailure();
        }

        DBObject lnkObj = cursor.next();
        Object evtId = lnkObj.get("evtid");

        /* store meta data */
        User user = signedIn(session);
        Object userId = null;

        if (user != null) {
            userId = user.objId;
        }

        DBObject access = new BasicDBObject("timestamp", new Date());
        access.put("user", userId);
        access.put("ip", getIP(request));

        DBObject elem = new BasicDBObject("access", access);
        DBObject update = new BasicDBObject("$push", elem);
        db.getCollection("shared_links").findAndModify(inQuery, update);

        cursor.close();

        BasicDBObject event = new BasicDBObject("_id", evtId);

        cursor = db.getCollection("eqs").find(event);

        if (!cursor.hasNext()) {
            return jsfailure();
        }

        /* needed to preserve the expected date format for JavaScript */
        /* TODO: dates are really difficult to parse between different languages
         * --> we need some consistent way to handle these problems */
        JsonObject json = new JsonObject();
        json.add("pos", gson.toJsonTree(lnkObj));
        json.add("eq", gson.toJsonTree(cursor.next()));

        cursor.close();

        return jssuccess(json);
    }

    @POST
    @Path("/copyToUser")
    @Produces(MediaType.APPLICATION_JSON)
    public String copyToUser(
        @Context final HttpServletRequest request,
        @FormParam("srcId") final String srcId,
        @CookieParam("server_cookie") final String session
    ) {
        Object[] required = {srcId};

        if (!checkParams(request, required)) {
            return jsfailure();
        }

        User user = signedIn(session);

        if (user == null) {
            return jsdenied();
        }

        /* do not copy the event again if there is already one copy for that
         * user */
        BasicDBObject inQuery = new BasicDBObject("copied", srcId);
        inQuery.put("user", user.objId);
        DBCursor cursor = db.getCollection("eqs").find(inQuery);

        if (cursor.hasNext()) {
            cursor.close();
            return jssuccess(new BasicDBObject("msg", "Copy already exists."));
        }

        cursor.close();

        inQuery = new BasicDBObject("_id", srcId);
        cursor = db.getCollection("eqs").find(inQuery);

        if (!cursor.hasNext()) {
            return jsfailure();
        }

        DBObject obj = cursor.next();
        cursor.close();

        String id = newRandomId(user.name);
        obj.put("user", user.objId);
        obj.put("_id", id);
        obj.put("id", id);
        obj.put("timestamp", new Date());
        obj.put("copied", srcId);

        db.getCollection("eqs").insert(obj);

        /* copy computation results */
        cursor = db.getCollection("comp")
            .find(new BasicDBObject("EventID", srcId));
        for (DBObject res : cursor) {
            res.put("EventID", id);
            res.removeField("_id");
            db.getCollection("comp").insert(res);
        }
        cursor.close();

        return jssuccess(
            new BasicDBObject("msg", "Event successfully copied.")
        );
    }

    private List<DBObject> msg(final int limit, final User user) {
        if (user == null) {
            return null;
        }

        DBCollection coll = db.getCollection("messages_sent");

        BasicDBObject inQuery = new BasicDBObject("SenderID", user.objId);

        /* query DB, sort the results by date and limit the number of returned
         * entries */
        DBCursor cursor = coll.find(inQuery)
            .sort(new BasicDBObject("CreatedTime", -1));

        if (limit > 0) {
            cursor = cursor.limit(limit);
        }

        List<DBObject> result = cursor.toArray();
        cursor.close();

        inQuery = new BasicDBObject("ReceiverID", user.objId);
        coll = db.getCollection("messages_received");
        cursor = coll.find(inQuery).sort(new BasicDBObject("CreatedTime", -1));

        if (limit > 0) {
            cursor = cursor.limit(limit);
        }

        for (DBObject obj : cursor) {
            DBCursor csrUser = db.getCollection("users")
                .find(new BasicDBObject("_id", obj.get("SenderID")));

            if (csrUser.hasNext()) {
                obj.put("From", (String) csrUser.next().get("username"));
            }

            obj.put("To", new String[] {user.name});
            obj.put("Dir", "in");

            result.add(obj);
        }

        cursor.close();

        Collections.sort(result, new DateComparator("CreatedTime", -1));

        /* add parent event as sub-object because we want to show it on click */
        for (DBObject msg : result) {

            DBCursor csr = db.getCollection("eqs")
                .find(new BasicDBObject("_id", msg.get("ParentId")));

            if (csr.hasNext()) {
                DBObject obj = csr.next();
                obj.removeField("image");
                msg.put("parentEvt", obj);
            }
        }

        return result;
    }

    @POST
    @Path("/data_insert_tfp")
    @Produces(MediaType.APPLICATION_JSON)
    public String data_insert_tfp(
        @Context final HttpServletRequest request,
        @FormParam("inst") final String inst,
        @FormParam("secret") final String secret,
        @FormParam("country") final String country,
        @FormParam("code") final String code,
        @FormParam("lon_real") final Double lon_real,
        @FormParam("lat_real") final Double lat_real,
        @FormParam("lon_sea") final Double lon_sea,
        @FormParam("lat_sea") final Double lat_sea,
        @FormParam("name") final String name,
        @FormParam("desc") final String desc,
        @FormParam("type") final String type
    ) {
        Object[] required = {
            inst, secret, country, code, lon_real, lat_real,
            lon_sea, lat_sea, name, desc, type
        };

        if (!checkParams(request, required)) {
            return jsfailure();
        }

        /* check if we got a valid institution and the correct secret */
        Inst instObj = institutions.get(inst);
        if (instObj == null || !instObj.secret.equals(secret)) {
            return jsdenied();
        }

        BasicDBObject tfp = new BasicDBObject();
        tfp.put("inst", instObj.objId);
        tfp.put("country", country);
        tfp.put("code", code);
        tfp.put("lon_real", lon_real);
        tfp.put("lat_real", lat_real);
        tfp.put("lon_sea", lon_sea);
        tfp.put("lat_sea", lat_sea);
        tfp.put("name", name);
        tfp.put("desc", desc);
        tfp.put("type", type);

        try {
            db.getCollection("tfps").insert(tfp);
        } catch (MongoException ex) {
            System.err.println(ex.getMessage());
        }

        return jssuccess();
    }

    /* TODO: split params into tokens and encode them separately */
    public static String sendPost(final String url, final String params) {
        HttpURLConnection con = null;

        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            DataOutputStream wr = new DataOutputStream(con.getOutputStream());
            wr.writeBytes(params);
            wr.flush();
            wr.close();

            BufferedReader in = new BufferedReader(
                new InputStreamReader(con.getInputStream())
            );
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            return response.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private Date parseIsoDate(final String dateStr) {
        /* used to convert to desired time format used by MongoDB */
        SimpleDateFormat sdf = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        );
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

        Date date;
        try {
            date = sdf.parse(dateStr);
        } catch (ParseException e) {
            return null;
        }

        return date;
    }

    private String jssuccess(final DBObject obj) {
        obj.put("status", "success");
        return obj.toString();
    }

    private String jssuccess() {
        return "{ \"status\": \"success\" }";
    }

    private String jsfailure() {
        return "{ \"status\": \"failure\" }";
    }

    private String jsfailure(final String msg) {
        return jsfailure(new BasicDBObject("msg", msg));
    }

    private String jsfailure(final DBObject obj) {
        obj.put("status", "failure");
        return obj.toString();
    }

    private String jsdenied() {
        return "{ \"status\": \"denied\" }";
    }

    private String jsdenied(final DBObject obj) {
        obj.put("status", "denied");
        return obj.toString();
    }

    /* this is nearly the same as 'jssuccess( DBObject obj )' but translates
     * the date objects better for later use in JavaScript */
    private String jssuccess(final JsonObject js) {
        js.add("status", gson.toJsonTree("success"));
        return js.toString();
    }

    @POST
    @Path("/getCFCZ")
    @Produces(MediaType.APPLICATION_JSON)
    public String getCFCZ() {
        DBCollection coll = db.getCollection("cfcz");
        DBCursor cursor = coll.find();

        return cursor.toArray().toString();
    }

    /* Flood - Prototype */
    @POST
    @Path("/flood_compute")
    @Produces(MediaType.APPLICATION_JSON)
    public String flood_compute(
        @Context final HttpServletRequest request,
        @FormParam("name") @DefaultValue("Custom") final String name,
        @FormParam("test") final String test,
        @CookieParam("server_cookie") final String session
    ) {
        Object[] required = {name};
        if (!checkParams(request, required)) {
            return jsfailure();
        }

        /* check if session is valid and if the user is logged in */
        User user = signedIn(session);
        if (user == null) {
            return jsdenied();
        }

        List<Location> locations;
        try {
            Type listType = new TypeToken<ArrayList<Location>>() { }.getType();
            locations = new Gson().fromJson(test, listType);
        } catch (JsonSyntaxException e) {
            return jsfailure("Invalid JSON input string.");
        }
        System.out.println(
            new Date() + ": User " + user.name
            + " requested a FLOOD computation."
        );

        /* upon here, we assume an authorized user */
        String retId = floodComputeForLocations(user, name, locations);
        return jssuccess(new BasicDBObject("_id", retId));
    }

    private String floodComputeForLocations(
        final User user,
        final String name,
        final List<Location> locations
    ) {
        /* create a unique ID that is not already present in the DB */
        String id = newRandomId(user.name);

        /* get current timestamp */
        Date timestamp = new Date();

        /* create new sub object that stores the properties */
        BasicDBObject sub = new BasicDBObject();
        sub.put("name", name);
        sub.put("locations", locations.size());

        /* create new DB object that should be added to the earthquake
         * collection */
        BasicDBObject obj = new BasicDBObject();
        obj.put("_id", id);
        obj.put("id", id);
        obj.put("user", user.objId);
        obj.put("timestamp", timestamp);
        obj.put("prop", sub);

        /* insert object into collection */
        db.getCollection("floodsims").insert(obj);

        /* create a new event */
        BasicDBObject event = new BasicDBObject();
        event.put("id", id);
        event.put("user", user.objId);
        event.put("timestamp", timestamp);
        event.put("event", "new");
        event.put("class", "flood");

        /* insert new event into 'events'-collection */
        db.getCollection("events").insert(event);

        System.out.println(locations.size());

        /* start request */
        Task task = new FloodTask(id, user, locations);
        task.setSlots(IScheduler.SLOT_NORMAL, IScheduler.SLOT_EXCLUSIVE);
        return request(id, task);
    }
}
