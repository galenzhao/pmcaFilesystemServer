package info.schnatterer.pmcaFilesystemServer;

import android.content.Context;
import android.media.ExifInterface;
import android.os.Environment;
import android.util.Base64;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.github.ma1co.openmemories.framework.DeviceInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.SimpleWebServer;

public class HttpServer extends SimpleWebServer {
    static final int PORT = 8080;
    static final String HOST = null; // bind to all interfaces by default
    static final String WWW_ROOT = "/";
    static final boolean QUIET = false;

    private static final char[] CREDENTIAL_ALPHABET = "ACDEFGHJKLMNPQRTUVWXY3467".toCharArray();
    private static final int USERNAME_LENGTH = 4;
    private static final int PASSWORD_LENGTH = 8;
    private static final String MIME_JSON = "application/json";
    private static final String MIME_CSS = "text/css";
    private static final String MIME_JAVASCRIPT = "text/javascript";
    private static final String MIME_JPEG = "image/jpeg";
    private static final String VIDEO_FALLBACK_THUMBNAIL = "assets/img/video-fallback.jpg";

    private final Context context;
    private final String username;
    private final String password;
    private final boolean exposeFullFilesystem;
    private final Set<String> allowedPaths;
    private final AvindexGpsWriter avindexGpsWriter;

    public HttpServer(Context context, boolean exposeFullFilesystem) {
        super(HOST, PORT, new File(WWW_ROOT).getAbsoluteFile(), QUIET);
        this.context = context.getApplicationContext();
        SecureRandom random = new SecureRandom();
        this.username = randomToken(random, USERNAME_LENGTH);
        this.password = randomToken(random, PASSWORD_LENGTH);
        this.exposeFullFilesystem = exposeFullFilesystem;
        this.allowedPaths = exposeFullFilesystem ? new HashSet<String>() : scanAllowedPaths();
        this.avindexGpsWriter = new AvindexGpsWriter(this.context);
        AvindexApiProbe.probeOnce();
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public Response serve(IHTTPSession session) {
        // Always drain POST/PUT bodies before any early return. NanoHTTPD keep-alive
        // otherwise leaves the body on the socket and the next request line becomes
        // "{json}POST /..." → BAD REQUEST: Syntax error. HTTP verb ... unhandled.
        Map<String, String> bodyFiles = new HashMap<String, String>();
        try {
            Method method = session.getMethod();
            if (Method.POST.equals(method) || Method.PUT.equals(method)) {
                session.parseBody(bodyFiles);
            }
        } catch (Exception e) {
            Logger.info("HttpServer.parseBody: " + e.getMessage());
        }
        try {
            if (!isAuthorized(session)) {
                return closeAfter(unauthorized());
            }
            return closeAfter(route(session, bodyFiles));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "Error" : e.getMessage();
            return closeAfter(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, message));
        }
    }

    private static Response closeAfter(Response response) {
        if (response != null) {
            response.addHeader("Connection", "close");
        }
        return response;
    }

    private Response route(IHTTPSession session, Map<String, String> bodyFiles) throws Exception {
        String uri = session.getUri();
        if ("/".equals(uri)) {
            return serveRoot();
        }
        if ("/gallery/".equals(uri)) {
            Response redirect = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "");
            redirect.addHeader("Location", "/gallery");
            return redirect;
        }
        if ("/gallery".equals(uri)) {
            return createResponseFromAsset("index.html", MIME_HTML);
        }
        if (uri.startsWith("/assets/")) {
            return serveAssetPath(uri);
        }
        if ("/thumbnail.do".equals(uri)) {
            return generateThumbnail(session);
        }
        if ("/api/list.do".equals(uri)) {
            return serveList(session);
        }
        if ("/api/meta.do".equals(uri)) {
            return serveMeta();
        }
        if ("/api/exif.do".equals(uri)) {
            return serveExif(session);
        }
        if ("/api/gps.do".equals(uri)) {
            if (!Method.POST.equals(session.getMethod())) {
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "POST required");
            }
            return setGps(session, bodyFiles);
        }
        if (!exposeFullFilesystem && !isAllowedMedia(uri)) {
            return forbidden();
        }
        return super.serve(session);
    }

    private Response serveRoot() {
        String heading = getDeviceInfo().getBrand() + " - " + getDeviceInfo().getModel();
        StringBuilder response = new StringBuilder("<html><head><title>" + heading
                + "</title><style><!--\n" + "span.dirname { font-weight: bold; }\n"
                + "span.filesize { font-size: 75%; }\n"
                + "// -->\n" + "</style>" + "</head><body><h1>" + heading + "</h1>");
        response.append("<p><a href=\"/gallery\">Gallery</a></p>");

        response.append("<h1>Videos</h1>");
        createFileList(FilesystemScanner.getVideosOnExternalStorage(), response);

        response.append("<h1>JPEGs</h1>");
        createFileList(FilesystemScanner.getJpegsOnExternalStorage(), response);

        response.append("<h1>RAW</h1>");
        createFileList(FilesystemScanner.getRawsOnExternalStorage(), response);

        response.append("<h1>Log File</h1>");
        createLinkToLogFile(response);
        response.append("<p>").append(logFile().getAbsolutePath()).append("</p>");

        if (exposeFullFilesystem) {
            response.append("<h1>File System</h1>");
            response.append(listDirectory("/", new File("/"))
                    .replaceFirst("<html>.*<body>", ""));
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, response.toString());
    }

    private Response serveMeta() throws JSONException {
        JSONObject meta = new JSONObject();
        meta.put("brand", getDeviceInfo().getBrand());
        meta.put("model", getDeviceInfo().getModel());
        meta.put("log", logFile().getAbsolutePath());
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, meta.toString());
    }

    private Response serveList(IHTTPSession session) throws JSONException {
        String queryType = queryParam(session, "type");
        if (queryType == null) {
            return badRequest("Missing query parameter \"type\"");
        }
        Set<String> types = new HashSet<String>(Arrays.asList(queryType.split(",")));
        List<File> fileList = FilesystemScanner.getFileOnExternalStorage(findFileExtensions(types));
        Collections.sort(fileList, new LastModifiedComparator());

        JSONArray list = new JSONArray();
        for (File file : fileList) {
            list.put(toJson(file));
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, list.toString());
    }

    private Set<String> findFileExtensions(Set<String> types) {
        Set<String> extensions = new HashSet<String>();
        if (types.contains("image")) {
            extensions.addAll(FilesystemScanner.jpegFormats);
        }
        if (types.contains("video")) {
            extensions.addAll(FilesystemScanner.videoFormats);
        }
        if (types.contains("raw")) {
            extensions.addAll(FilesystemScanner.rawFormats);
        }
        return extensions;
    }

    private JSONObject toJson(File file) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("name", file.getName());
        entry.put("size", file.length());
        entry.put("date", file.lastModified());
        entry.put("file", file.getPath());
        entry.put("preview", file.getPath());
        entry.put("type", "image");
        entry.put("thumbnail", "/thumbnail.do?f=" + file.getPath());

        String fileExt = getExtension(file);
        if (FilesystemScanner.rawFormats.contains("." + fileExt.toLowerCase(Locale.US))) {
            entry.put("type", "raw");
        } else if (FilesystemScanner.videoFormats.contains("." + fileExt.toLowerCase(Locale.US))) {
            entry.put("type", "video");
        }
        return entry;
    }

    private Response serveExif(IHTTPSession session) throws JSONException {
        String imagePath = queryParam(session, "f");
        if (imagePath == null) {
            return badRequest("Missing query parameter \"f\"");
        }
        if (!exposeFullFilesystem && !isAllowedMedia(imagePath)) {
            return forbidden();
        }
        File imageFile = new File(imagePath);
        JSONObject exifJson = new JSONObject();
        try {
            exifJson.put("Name", imageFile.getName());
            exifJson.put("LastModified", imageFile.lastModified());
            exifJson.put("success", true);
            addPreview(imageFile, exifJson);
            addMetadata(imageFile, exifJson);
            addGps(imageFile, exifJson);
        } catch (ImageProcessingException e) {
            exifJson.put("success", false);
        } catch (MetadataException e) {
            exifJson.put("success", false);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, e.getMessage());
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, exifJson.toString());
    }

    private void addMetadata(File imageFile, JSONObject exifJson)
            throws ImageProcessingException, IOException, MetadataException, JSONException {
        Metadata metadata = ImageMetadataReader.readMetadata(imageFile);
        for (Directory directory : collectMetadataDirectories(metadata)) {
            addDescriptionIfExists(ExifDirectoryBase.TAG_FNUMBER, "FNumber", directory, exifJson);
            addIntIfExists(ExifDirectoryBase.TAG_FOCAL_LENGTH, "FocalLength", directory, exifJson);
            addDescriptionIfExists(ExifDirectoryBase.TAG_EXPOSURE_TIME, "ExposureTime", directory, exifJson);
            addIntIfExists(ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH, "ImageWidth", directory, exifJson);
            addIntIfExists(ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT, "ImageLength", directory, exifJson);
            addIntIfExists(ExifDirectoryBase.TAG_ISO_EQUIVALENT, "ISOSpeedRatings", directory, exifJson);
            addDescriptionIfExists(ExifDirectoryBase.TAG_MODEL, "Model", directory, exifJson);
            addDescriptionIfExists(ExifDirectoryBase.TAG_LENS_MODEL, "LensModel", directory, exifJson);
            addDescriptionIfExists(ExifDirectoryBase.TAG_LENS_SPECIFICATION, "LensSpecification", directory, exifJson);
        }
    }

    private void addIntIfExists(int tag, String name, Directory directory, JSONObject json)
            throws MetadataException, JSONException {
        if (directory.containsTag(tag)) {
            json.put(name, directory.getInt(tag));
        }
    }

    private void addDescriptionIfExists(int tag, String name, Directory directory, JSONObject json)
            throws MetadataException, JSONException {
        if (directory.containsTag(tag)) {
            json.put(name, directory.getDescription(tag));
        }
    }

    private Collection<Directory> collectMetadataDirectories(Metadata metadata) throws MetadataException {
        Set<Directory> directories = new HashSet<Directory>();
        directories.addAll(metadata.getDirectoriesOfType(ExifIFD0Directory.class));
        directories.addAll(metadata.getDirectoriesOfType(ExifSubIFDDirectory.class));
        if (directories.isEmpty()) {
            throw new MetadataException("No entries for ExifSubIFDDirectory found");
        }
        return directories;
    }

    private void addPreview(File imageFile, JSONObject exifJson) throws JSONException {
        String fileExt = "." + getExtension(imageFile).toLowerCase(Locale.US);
        if (FilesystemScanner.rawFormats.contains(fileExt)) {
            File jpgFile = changeExtension(imageFile, ".JPG");
            if (jpgFile.exists()) {
                exifJson.put("preview", jpgFile.getPath());
            } else {
                exifJson.put("preview", "/thumbnail.do?f=" + imageFile.getPath());
            }
        } else if (FilesystemScanner.videoFormats.contains(fileExt)) {
            exifJson.put("preview", "/" + VIDEO_FALLBACK_THUMBNAIL);
        }
    }

    private Response generateThumbnail(IHTTPSession session) {
        String imagePath = queryParam(session, "f");
        if (imagePath == null) {
            return badRequest("Missing query parameter \"f\"");
        }
        if (!exposeFullFilesystem && !isAllowedMedia(imagePath)) {
            return forbidden();
        }
        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, imagePath + " not found");
        }

        String fileExt = "." + getExtension(imageFile).toLowerCase(Locale.US);
        if (FilesystemScanner.rawFormats.contains(fileExt)) {
            return generateRawThumbnail(imageFile);
        }
        if (FilesystemScanner.jpegFormats.contains(fileExt)) {
            return generateJpgThumbnail(imagePath);
        }
        if (FilesystemScanner.videoFormats.contains(fileExt)) {
            return generateVideoThumbnail();
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT,
                "Filetype not supported for " + imagePath);
    }

    private Response generateRawThumbnail(File imageFile) {
        try {
            byte[] thumbnail = readThumbnailBytes(imageFile);
            return jpegResponse(thumbnail);
        } catch (IOException e) {
            File jpgFile = changeExtension(imageFile, ".JPG");
            if (jpgFile.exists()) {
                return generateJpgThumbnail(jpgFile.getPath());
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT,
                    "Filetype not supported for " + imageFile.getPath());
        }
    }

    private Response generateJpgThumbnail(String imagePath) {
        try {
            ExifInterface exif = new ExifInterface(imagePath);
            byte[] thumbnail = exif.getThumbnail();
            if (thumbnail == null || thumbnail.length == 0) {
                throw new IOException("Failed to read thumbnail from EXIF in " + imagePath);
            }
            return jpegResponse(thumbnail);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
        }
    }

    private Response generateVideoThumbnail() {
        try {
            return createResponseFromAsset(VIDEO_FALLBACK_THUMBNAIL, MIME_JPEG);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
        }
    }

    private Response jpegResponse(byte[] thumbnail) {
        ByteArrayInputStream input = new ByteArrayInputStream(thumbnail);
        Response response = newFixedLengthResponse(Response.Status.OK, MIME_JPEG, input, thumbnail.length);
        response.addHeader("Accept-Ranges", "bytes");
        return response;
    }

    private byte[] readThumbnailBytes(File imageFile) throws IOException {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(imageFile);
            ExifThumbnailDirectory thumbnailDirectory = metadata.getFirstDirectoryOfType(ExifThumbnailDirectory.class);
            if (thumbnailDirectory == null) {
                throw new IOException("No thumbnail");
            }
            long offset = thumbnailDirectory.getLong(ExifThumbnailDirectory.TAG_THUMBNAIL_OFFSET);
            int length = thumbnailDirectory.getInt(ExifThumbnailDirectory.TAG_THUMBNAIL_LENGTH);
            if (length <= 0 || offset < 0) {
                throw new IOException("Invalid thumbnail");
            }
            byte[] bytes = new byte[length];
            RandomAccessFile handle = new RandomAccessFile(imageFile, "r");
            try {
                handle.seek(offset);
                handle.readFully(bytes);
            } finally {
                handle.close();
            }
            return bytes;
        } catch (ImageProcessingException e) {
            throw new IOException("Reading thumbnail failed", e);
        } catch (MetadataException e) {
            throw new IOException("Reading thumbnail failed", e);
        }
    }

    private Response serveAssetPath(String uri) {
        String path = uri;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.length() == 0 || path.contains("..")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Failed to load " + uri);
        }
        try {
            return createResponseFromAsset(path, findMimeType(path));
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Failed to load " + uri);
        }
    }

    private Response createResponseFromAsset(String assetPath, String mime) throws IOException {
        InputStream input = context.getAssets().open(assetPath);
        return newFixedLengthResponse(Response.Status.OK, mime, input, input.available());
    }

    private String findMimeType(String path) {
        if (path.endsWith(".css")) {
            return MIME_CSS;
        }
        if (path.endsWith(".js")) {
            return MIME_JAVASCRIPT;
        }
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return MIME_JPEG;
        }
        if (path.endsWith(".woff")) {
            return "font/woff";
        }
        if (path.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (path.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (path.endsWith(".eot")) {
            return "application/vnd.ms-fontobject";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return MIME_HTML;
    }

    private static String queryParam(IHTTPSession session, String name) {
        Map<String, List<String>> query = decodeParameters(session.getQueryParameterString());
        List<String> values = query.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private static String getExtension(File file) {
        return file.getName().substring(file.getName().lastIndexOf(".") + 1);
    }

    private static File changeExtension(File file, String newExtension) {
        String name = file.getName();
        String withoutExtension = name.substring(0, name.lastIndexOf("."));
        return new File(file.getParentFile(), withoutExtension + newExtension);
    }

    private boolean isAuthorized(IHTTPSession session) {
        String header = session.getHeaders().get("authorization");
        if (header == null || header.length() < 6 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }

        byte[] decodedBytes;
        try {
            decodedBytes = Base64.decode(header.substring(6).trim(), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return false;
        }

        String decoded;
        try {
            decoded = new String(decodedBytes, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return false;
        }

        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        return constantTimeEquals(decoded.substring(0, colon), username)
                && constantTimeEquals(decoded.substring(colon + 1), password);
    }

    private Response unauthorized() {
        Response response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized");
        response.addHeader("WWW-Authenticate", "Basic realm=\"pmca-" + username + "\"");
        return response;
    }

    private Response forbidden() {
        return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden");
    }

    private Response badRequest(String message) {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, message);
    }

    private boolean isAllowedMedia(String uri) {
        try {
            File file = new File(uri).getCanonicalFile();
            try {
                if (file.getCanonicalPath().equals(logFile().getCanonicalPath())) {
                    return true;
                }
            } catch (IOException ignored) {
            }
            if (allowedPaths.contains(file.getPath())) {
                return true;
            }
            String path = file.getPath();
            String root = Environment.getExternalStorageDirectory().getCanonicalPath();
            if (!path.startsWith(root + File.separator)) {
                return false;
            }
            String name = file.getName().toLowerCase(Locale.US);
            return name.endsWith(".jpg") || name.endsWith(".arw") || name.endsWith(".mp4") || name.endsWith(".mts");
        } catch (IOException e) {
            return false;
        }
    }

    private static Set<String> scanAllowedPaths() {
        Set<String> paths = new HashSet<String>();
        addCanonicalPaths(paths, FilesystemScanner.getVideosOnExternalStorage());
        addCanonicalPaths(paths, FilesystemScanner.getJpegsOnExternalStorage());
        addCanonicalPaths(paths, FilesystemScanner.getRawsOnExternalStorage());
        return paths;
    }

    private static void addCanonicalPaths(Set<String> paths, List<File> files) {
        for (File file : files) {
            try {
                paths.add(file.getCanonicalPath());
            } catch (IOException ignored) {
            }
        }
    }

    private static String randomToken(SecureRandom random, int length) {
        char[] token = new char[length];
        for (int i = 0; i < length; i++) {
            token[i] = CREDENTIAL_ALPHABET[random.nextInt(CREDENTIAL_ALPHABET.length)];
        }
        return new String(token);
    }

    private static boolean constantTimeEquals(String left, String right) {
        byte[] leftBytes;
        byte[] rightBytes;
        try {
            leftBytes = left.getBytes("UTF-8");
            rightBytes = right.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            return false;
        }
        if (leftBytes.length != rightBytes.length) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < leftBytes.length; i++) {
            difference |= leftBytes[i] ^ rightBytes[i];
        }
        return difference == 0;
    }


    private void addGps(File imageFile, JSONObject exifJson) throws JSONException {
        File jpeg = GpsExifWriter.resolveReadableJpeg(imageFile);
        if (jpeg == null) {
            jpeg = imageFile;
        }
        float[] latLong = GpsExifWriter.readLatLong(jpeg);
        if (latLong != null) {
            exifJson.put("GPSLatitude", latLong[0]);
            exifJson.put("GPSLongitude", latLong[1]);
            exifJson.put("hasGps", true);
        } else {
            exifJson.put("hasGps", false);
        }
    }

    private Response setGps(IHTTPSession session, Map<String, String> bodyFiles) throws Exception {
        // Body already drained in serve(). Prefer form fields (reliable on NanoHTTPD),
        // fall back to raw JSON postData.
        Map<String, String> parms = session.getParms();
        double lat;
        double lon;
        Double altitude;
        JSONArray paths;

        if (parms != null && parms.get("lat") != null && parms.get("lon") != null
                && parms.get("files") != null) {
            lat = Double.parseDouble(parms.get("lat"));
            lon = Double.parseDouble(parms.get("lon"));
            String altRaw = parms.get("alt");
            altitude = (altRaw == null || altRaw.length() == 0)
                    ? null
                    : Double.valueOf(Double.parseDouble(altRaw));
            String filesRaw = parms.get("files");
            paths = filesRaw.trim().startsWith("[")
                    ? new JSONArray(filesRaw)
                    : new JSONArray().put(filesRaw);
        } else {
            String body = bodyFiles != null ? bodyFiles.get("postData") : null;
            if (body == null || body.length() == 0) {
                return badRequest("Missing body (expected form lat/lon/files or JSON)");
            }
            JSONObject request = new JSONObject(body);
            if (!request.has("lat") || !request.has("lon") || !request.has("files")) {
                return badRequest("JSON must include lat, lon, and files");
            }
            lat = request.getDouble("lat");
            lon = request.getDouble("lon");
            altitude = request.has("alt") && !request.isNull("alt")
                    ? Double.valueOf(request.getDouble("alt"))
                    : null;
            paths = request.getJSONArray("files");
        }
        if (paths.length() == 0) {
            return badRequest("files must not be empty");
        }

        Logger.info("HttpServer.setGps request lat=" + lat + " lon=" + lon
                + " alt=" + (altitude == null ? "null" : String.valueOf(altitude))
                + " files=" + paths.length()
                + " first=" + (paths.length() > 0 ? paths.optString(0) : ""));

        // JPEG+RAW of the same DCF number share one Avindex row — one updateGps writes both.
        LinkedHashMap<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < paths.length(); i++) {
            String path = paths.getString(i);
            String key = AvindexGpsWriter.stillGroupKey(new File(path));
            List<String> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<String>();
                groups.put(key, group);
            }
            group.add(path);
        }

        JSONArray results = new JSONArray();
        int ok = 0;
        int failed = 0;
        int writes = 0;
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            List<String> group = entry.getValue();
            String primary = pickGpsWritePrimary(group);
            Logger.info("HttpServer.setGps group=" + entry.getKey()
                    + " primary=" + primary + " siblings=" + group.size());

            AvindexGpsWriter.Result writeResult = null;
            String error = null;
            boolean forbidden = false;
            try {
                if (!exposeFullFilesystem && !isAllowedMedia(primary)) {
                    forbidden = true;
                    error = "Forbidden";
                    Logger.info("HttpServer.setGps forbidden path=" + primary);
                } else {
                    writeResult = avindexGpsWriter.write(new File(primary), lat, lon, altitude);
                    writes++;
                    if (!writeResult.success) {
                        error = writeResult.error;
                    }
                }
            } catch (Exception e) {
                Logger.error("HttpServer.setGps exception path=" + primary, e);
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }

            boolean success = !forbidden && writeResult != null && writeResult.success;
            for (int i = 0; i < group.size(); i++) {
                String path = group.get(i);
                JSONObject item = new JSONObject();
                item.put("file", path);
                item.put("engine", "avindex");
                item.put("group", entry.getKey());
                item.put("primary", primary);
                boolean isPrimary = primary.equals(path);
                item.put("wrote", isPrimary && success);
                item.put("deduped", !isPrimary);
                if (success) {
                    item.put("success", true);
                    if (writeResult.written != null) {
                        item.put("written", writeResult.written);
                    }
                    item.put("raw", new File(path).getName().toLowerCase(Locale.US).endsWith(".arw")
                            || new File(path).getName().toLowerCase(Locale.US).endsWith(".raw"));
                    if (!isPrimary) {
                        item.put("note", "Same DCF as " + new File(primary).getName()
                                + "; single Avindex updateGps covers JPEG+RAW");
                    }
                    ok++;
                } else {
                    item.put("success", false);
                    item.put("error", error != null ? error : "Write failed");
                    item.put("raw", new File(path).getName().toLowerCase(Locale.US).endsWith(".arw")
                            || new File(path).getName().toLowerCase(Locale.US).endsWith(".raw"));
                    failed++;
                }
                results.put(item);
            }
        }

        if (ok > 0) {
            avindexGpsWriter.refreshDatabase();
        }

        JSONObject response = new JSONObject();
        response.put("success", failed == 0);
        response.put("ok", ok);
        response.put("failed", failed);
        response.put("writes", writes);
        response.put("engine", "avindex");
        response.put("lat", lat);
        response.put("lon", lon);
        if (altitude != null) {
            response.put("alt", altitude);
        }
        response.put("results", results);
        Logger.info("HttpServer.setGps done ok=" + ok + " failed=" + failed
                + " writes=" + writes + " groups=" + groups.size());
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, response.toString());
    }

    /** Prefer JPEG over ARW when both siblings are selected. */
    private static String pickGpsWritePrimary(List<String> group) {
        String best = group.get(0);
        int bestScore = AvindexGpsWriter.stillWritePreference(new File(best));
        for (int i = 1; i < group.size(); i++) {
            String path = group.get(i);
            int score = AvindexGpsWriter.stillWritePreference(new File(path));
            if (score < bestScore) {
                best = path;
                bestScore = score;
            }
        }
        return best;
    }

    private void createLinkToLogFile(StringBuilder response) {
        response.append("<a href=\"");
        File logFile = logFile();
        response.append(logFile.getAbsolutePath());
        response.append("\">");
        response.append(logFile.getName());
        response.append("</a>");
    }

    private File logFile() {
        return Logger.getFile();
    }

    private DeviceInfo getDeviceInfo() {
        return DeviceInfo.getInstance();
    }

    private void createFileList(List<File> files, StringBuilder response) {
        response.append("<ul>");
        for (File file : files) {
            response.append("<li><a href=\"");
            response.append(file.getAbsolutePath());
            response.append("\">");
            response.append(file.getName());
            response.append("</a></li>");
        }
        response.append("</ul>");
    }

    private static class LastModifiedComparator implements Comparator<File> {
        @Override
        public int compare(File file1, File file2) {
            long difference = file1.lastModified() - file2.lastModified();
            if (difference < 0) {
                return 1;
            }
            if (difference == 0) {
                return 0;
            }
            return -1;
        }
    }
}
