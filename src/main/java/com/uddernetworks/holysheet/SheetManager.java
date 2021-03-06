package com.uddernetworks.holysheet;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.sheets.v4.Sheets;
import com.uddernetworks.holysheet.io.SheetIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.uddernetworks.holysheet.utility.Utility.DRIVE_FIELDS;
import static com.uddernetworks.holysheet.utility.Utility.getCollectionFirst;

public class SheetManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SheetManager.class);

    public static final Pattern PATH_REGEX = Pattern.compile("^\\/([\\w-]+?(\\/[\\w-]+?){0,1})*\\/$");

    private final Drive drive;
    private final Sheets sheets;
    private SheetIO sheetIO;

    private File sheetStore;

    public SheetManager(Drive drive, Sheets sheets) {
        this.drive = drive;
        this.sheets = sheets;
        this.sheetIO = new SheetIO(this, drive, sheets);
    }

    /**
     * Return a {@link File} from drive with the id provided.
     *
     * @param id The id of the file.
     * @return {@link File} if found, otherwise {@code null}.
     * @throws IOException An exception from the drive request.
     */
    public File getFile(String id) throws IOException {
        try {
            return drive.files().get(id).execute();
        } catch (GoogleJsonResponseException exception) {
            if (exception.getStatusCode() == 404) {
                return null;
            } else throw exception;
        }
    }

    /**
     * Return a {@link File} from drive with the id provided; and the fields
     * to request from a partial response from Drive.
     *
     * @param id The id of the file.
     * @return {@link File} if found, otherwise {@code null}.
     * @throws IOException An exception from the drive request.
     */
    public File getFile(String id, String fields) throws IOException {
        return drive.files().get(id).setFields(fields).execute();
    }

    /**
     * Return the id of a google drive file matching the given name, not necessarily inside the sheetStore folder.
     *
     * @param name The name to match.
     * @return {@link Optional} wrapping the {@link String} name.
     */
    public Optional<String> getIdOfName(String name) {
        return getIdOfName(name, true);
    }

    /**
     * Return the id of a google drive file matching the given name.
     *
     * @param name         The name to match.
     * @param inSheetStore Whether this file must be in the sheetStore folder.
     * @return {@link Optional} wrapping the {@link String} name.
     */
    public Optional<String> getIdOfName(String name, boolean inSheetStore) {
        try {
            var query = "name contains '" + name.replace("'", "") + "'";

            if (inSheetStore) {
                query = "parents in '" + getSheetStore().getId() + "' and " + query;
            }

            var files = getFiles(1, query, Mime.FOLDER);
            return getCollectionFirst(files).map(File::getId);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public List<File> listUploads() {
        return listUploads("/", false, false);
    }

    /**
     * Returns a list of {@link File}s that match the path, and the properties listed.
     *
     * @param path    The path of the file, must be valid and match the regex.
     * @param starred Whether the file must be starred or not.
     * @param trashed Whether the file must have been removed.
     * @return {@link List} of files; or an empty list if an exception occured.
     */
    public List<File> listUploads(String path, boolean starred, boolean trashed) {
        try {
            if (path.isBlank() || !PATH_REGEX.matcher(path).matches()) {
                path = "/";
            }

            var pathQuery = starred ? "" : " and properties has { key='path' and value='" + path + "' }";
            var extra = starred ? " and properties has { key='starred' and value='true' }" : "";
            return getFiles(-1, "properties has { key='directParent' and value='true' }" + pathQuery + " and trashed = " + trashed + extra, Mime.FOLDER);
        } catch (IOException e) {
            LOGGER.error("An error occurred while listing uploads", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get all the sheets in the drive, that has a parent.
     *
     * @return List of {@link File}s that are sheets.
     * @throws IOException If the drive request throws an exception.
     */
    public List<File> getAllSheets() throws IOException {
        return getFiles(-1, "properties has { key='directParent' and value='true' }", Mime.FOLDER);
    }

    /**
     * Return all sheets that has {@code id} as a parent.
     *
     * @param id String id of the sheet.
     * @return List of {@link File}s that are sheets.
     * @throws IOException If the drive request throws an exception.
     */
    public List<File> getAllSheets(String id) throws IOException {
        return getFiles(-1, "parents in '" + id + "'", Mime.SHEET);
    }

    /**
     * Create a new drive folder with no parent - i.e. in the main drive area.
     *
     * @param name Name of the folder.
     * @return {@link File} representing the folder; or null if failed.
     * @throws IOException If the drive request throws an exception.
     */
    public File createFolder(String name) throws IOException {
        return createFolder(name, null);
    }

    /**
     * Create a new drive folder with the specified parent.
     *
     * @param name   Name of the folder.
     * @param parent Parent file.
     * @return {@link File} representing the folder; or null if failed.
     * @throws IOException If the drive request throws an exception.
     */
    public File createFolder(String name, File parent) throws IOException {
        return createFolder(name, parent, null);
    }

    /**
     * Create a new drive folder with the specified parent and properties.
     *
     * @param name       Name of the folder.
     * @param parent     Parent file.
     * @param properties Properties to set to the folder.
     * @return {@link File} representing the folder; or null if failed.
     * @throws IOException If the drive request throws an exception.
     */
    public File createFolder(String name, File parent, Map<String, String> properties) throws IOException {
        return drive.files().create(new File()
                .setMimeType(Mime.FOLDER.getMime())
                .setParents(parent == null ? null : Collections.singletonList(parent.getId()))
                .setProperties(properties)
                .setName(name))
                .setFields(DRIVE_FIELDS).execute();
    }

    /**
     * Adds or overwrites  properties to the given file.
     *
     * @param file       The file
     * @param properties The properties to add or overwrite
     */
    public void addProperties(File file, Map<String, String> properties) throws IOException {
        var combined = new HashMap<>(file.getProperties() == null ? Collections.emptyMap() : file.getProperties());
        combined.putAll(properties);
        setProperties(file, combined);
    }

    /**
     * Adds or overwrites  properties to the given file.
     *
     * @param id         The ID of the file
     * @param properties The properties to add or overwrite
     */
    public void addProperties(String id, Map<String, String> properties) throws IOException {
        var file = getFile(id, "id, properties");
        var combined = new HashMap<>(file.getProperties());
        combined.putAll(properties);
        setProperties(file, combined);
    }

    /**
     * Sets the file's properties to the given map. Any properties previously set that are not in the properties
     * argument will be cleared.
     *
     * @param file       The file
     * @param properties The properties to set
     */
    public void setProperties(File file, Map<String, String> properties) throws IOException {
        setProperties(file.getId(), properties);
    }

    /**
     * Sets the file's properties to the given map. Any properties previously set that are not in the properties
     * argument will be cleared.
     *
     * @param id         The ID of the file
     * @param properties The properties to set
     */
    public void setProperties(String id, Map<String, String> properties) throws IOException {
        var meta = new File();
        meta.setProperties(properties);
        drive.files().update(id, meta).setFields("id, properties").execute();
    }

    /**
     * Gets the files in the google drive with the given limit and matching mime types.
     *
     * @param limit The limit of files to find (-1 for all files, USE SPARINGLY)
     * @param mimes The mime types to match for
     * @return The list of files
     * @throws IOException
     */
    public List<File> getFiles(int limit, Mime... mimes) throws IOException {
        return getFiles(limit, null, mimes);
    }

    /**
     * Gets the files in the google drive with the given limit and matching mime types.
     *
     * @param limit The limit of files to find (-1 for all files, USE SPARINGLY)
     * @param query An additional query to search for
     * @param mimes The mime types to match for
     * @return The list of files
     * @throws IOException
     */
    public List<File> getFiles(int limit, String query, Mime... mimes) throws IOException {
        return getFiles(limit, query, DRIVE_FIELDS, mimes);
    }

    /**
     * Gets the files in the google drive with the given limit and matching mime types.
     *
     * @param limit  The limit of files to find (-1 for all files, USE SPARINGLY)
     * @param query  An additional query to search for
     * @param fields The fields to request
     * @param mimes  The mime types to match for
     * @return The list of files.
     * @throws IOException
     */
    public List<File> getFiles(int limit, String query, String fields, Mime... mimes) throws IOException {
        limit = Math.min(-1, limit);
        var mimeTypes = Arrays.stream(mimes).map(Mime::getMime).collect(Collectors.toUnmodifiableSet());
        var foundFiles = new ArrayList<File>();

        var pageToken = "";
        do {
            var result = getPagesFiles(pageToken, 50, mimes, query, fields);
            pageToken = result.getNextPageToken();
            var files = result.getFiles();
            if (files == null || files.isEmpty()) {
                break;
            }

            for (var file : files) {
                if (!mimeTypes.contains(file.getMimeType())) {
                    continue;
                }

                foundFiles.add(file);

                if (--limit == 0) {
                    return foundFiles;
                }
            }
        } while (pageToken != null);

        return foundFiles;
    }

    private FileList getPagesFiles(String pageToken, int pageSize, Mime[] mimes, String query, String fields) throws IOException {
        var builder = drive.files().list()
                .setPageSize(pageSize)
                .setFields("nextPageToken, files(" + fields + ")");

        if (pageToken != null && !pageToken.isBlank()) {
            builder.setPageToken(pageToken);
        }

        var q = getQueryFromMime(mimes).orElse("");

        if (query != null && !query.isBlank()) {
            if (q.isBlank()) {
                q = query;
            } else {
                q += " and " + query;
            }
        }

        builder.setQ(q);

        return builder.execute();
    }

    private Optional<String> getQueryFromMime(Mime[] mimes) {
        if (mimes.length == 0) return Optional.empty();
        var base = new StringBuilder();
        for (var mime : mimes) {
            base.append("mimeType = '").append(mime.getMime()).append("' or ");
        }

        var q = base.substring(0, base.length() - 4);
        return Optional.of(q);
    }

    /**
     * Creates and returns a folder called 'sheetStore' to store holysheet files and chunks in.
     * If the folder happens to already exist, unexpectedly, then it is returned. It is unexpected as
     * if the folder were to already be created, the method {@link #getSheetStore()} should be used.
     *
     * @return {@link File} representing the 'sheetStore' folder.
     * @throws IOException May be thrown when creating the folder.
     */
    private File createSheetStore() throws IOException {
        return getCollectionFirst(getFiles(1, "name = 'sheetStore'", Mime.FOLDER)).orElseGet(() -> {
            try {
                return createFolder("sheetStore");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Returns the 'sheetStore' folder from a variable. If it is not stored locally,
     * then {@link #createSheetStore()} will be invoked. This will either fetch the folder
     * from google drive, or create it and return it.
     * @return {@link File} representing the 'sheetStore' folder.
     */
    public File getSheetStore() {
        try {
            if (sheetStore == null) {
                return (sheetStore = createSheetStore());
            }

            return sheetStore;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public SheetIO getSheetIO() {
        return sheetIO;
    }
}
