/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2018 - 2021 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.images.servers.omero;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import javax.naming.OperationNotSupportedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.servers.omero.OmeroObjects.Group;
import qupath.lib.images.servers.omero.OmeroObjects.OmeroObjectType;
import qupath.lib.io.GsonTools;

/**
 * Class representing an OMERO Web Client. This class takes care of 
 * logging in, keeping the connection alive and logging out.
 * 
 * @author Melvin Gelbard
 */
public class OmeroWebClient {

	final private static Logger logger = LoggerFactory.getLogger(OmeroWebClient.class);

	private final static String URL_SERVERS = "url:servers";
	private final static String URL_LOGIN = "url:login";
	private final static String URL_TOKEN = "url:token";
	private Map<String, String> omeroURLs = new HashMap<>();
	
	/**
	 * List of all URIs supported by this client. This list should be populated after calls to {@link #canBeAccessed(URI, OmeroObjectType)}.
	 */
	private ObservableList<URI> uris = FXCollections.observableArrayList();
	
	/**
	 * 'Clean' URI representing the server's URI (<b>not</b> its images). <p> See {@link OmeroTools#getServerURI(URI)}.
	 */
	private URI serverURI;
	
	/**
	 * The username might be empty (public), and might also change (user switching account)
	 */
	private StringProperty username;
	
	/**
	 * The default group to switch to when browsing an OMERO server. Might change if user switches account/logs off
	 */
	private Group defaultGroup;
	
	/**
	 * User ID fetched from the login request response. Can be used to match {@code Owner}s in the browser.
	 */
	private int userId;

  private String sessionId;
  private boolean hasMicroservice = false;
	
	/**
	 * Logged in property (modified by login/loggedIn/logout/timer)
	 */
	private BooleanProperty loggedIn;
	
	
	private OmeroServerInfo omeroServerInfo;
	private OmeroAPI supportedVersions;
	private OmeroAPIVersion APIVersion;
	private String token;
	
	private Timer timer;
	
	static OmeroWebClient create(URI serverURI, boolean startTimer) throws JsonSyntaxException, MalformedURLException, IOException, URISyntaxException {
		// Clean server URI (filter out wrong URIs and get rid of unnecessary characters)
		var cleanServerURI = new URL(serverURI.getScheme(), serverURI.getHost(), serverURI.getPort(), "").toURI();
		
		// Create OmeroWebClient with the serverURI
		OmeroWebClient client = new OmeroWebClient(cleanServerURI);
		
		// Start timer to keep connection alive
		if (startTimer)
			client.startTimer();
		
		return client;
	}

	private OmeroWebClient(final URI serverUri) throws JsonSyntaxException, MalformedURLException, IOException {
		this.serverURI = serverUri;
		this.username = new SimpleStringProperty("");
		this.defaultGroup = null;
		this.userId = -1;
		this.loggedIn = new SimpleBooleanProperty(false);
		loadURLs();
	}

	synchronized void startTimer() {
		if (timer != null)
			return;
		timer = new Timer("omero-keep-alive", true);
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (keepAlive() == -1) {
					this.cancel();
					loggedIn.set(false);
				}
			}
		}, 60000L, 60000L);
	}

	String authenticate(final PasswordAuthentication authentication, final int serverID) throws Exception {
		var handler = CookieHandler.getDefault();
		handler = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		CookieHandler.setDefault(handler);

		if (this.token == null)
			this.token = getCSRFToken();

		String url = omeroURLs.get(URL_LOGIN);
		HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
		connection.setRequestProperty("X-CSRFToken", this.token);
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Referer", url + ":" + omeroServerInfo.port);
		connection.setUseCaches(false);

		connection.setDoInput(true);
		connection.setDoOutput(true);
		
		
		var charset = StandardCharsets.UTF_8;
		try (OutputStream stream = connection.getOutputStream()) {
			// To avoid storing the password in a String: create ByteBuffers and concatenate them, then convert to byte[]
			String s = String.join("&", "server=" + serverID, "username=" + authentication.getUserName(), "password=");
			CharBuffer charBuffer = CharBuffer.wrap(authentication.getPassword());
			byte[] sBytes = s.getBytes(charset);
			byte[] out = new byte[sBytes.length + charBuffer.length() * 4];
			ByteBuffer byteBuffer = ByteBuffer.wrap(out);
			byteBuffer.put(sBytes);
			var encoder = charset.newEncoder();
			encoder.encode(charBuffer, byteBuffer, true);
			stream.write(out, 0, byteBuffer.position());
			
			// Fill the traces of password with '0'
			Arrays.fill(authentication.getPassword(), (char) 0);
			Arrays.fill(out, (byte)0);
			Arrays.fill(charBuffer.array(), (char)0);
			Arrays.fill(byteBuffer.array(), (byte)0);
			encoder.reset();
			System.gc();
		}

//	        var client = HttpClient.newBuilder()
//	        		.build();
//	        var request = HttpRequest.newBuilder(URI.create(url))
//	        		.header("X-CSRFToken", this.token)
//	        		.header("Referer", url)
//	        		.header("Cookie", "csrftoken=" + this.token)
//	        		.POST(BodyPublishers.ofString(s)).build();
//	        try {
//				System.err.println(client.send(request, BodyHandlers.ofString()).body());
//			} catch (InterruptedException e) {
//				e.printStackTrace();
//			}

    String rtn = null;
		try (InputStream input = connection.getInputStream()) {
			rtn = GeneralTools.readInputStreamAsString(input);
		}

    // look for session ID in existing session cookies
    // this will throw an IOException if the 'sessionid' cookie is not found
    // the session ID is used later when retrieving raw tiles from the microservice
    sessionId = null;
    List<HttpCookie> cookies = ((CookieManager) handler).getCookieStore().getCookies();
    for (HttpCookie cookie : cookies) {
      if (cookie.getName().equals("sessionid")) {
        sessionId = cookie.getValue();
      }
    }
    if (sessionId == null) {
      throw new IOException("Could not find valid 'sessionid' cookie");
    }

    // check for image region microservice - enables raw tile retrieval
    // see https://github.com/glencoesoftware/omero-ms-image-region
    //
    // the session ID retrieved above is not required to perform this check,
    // but both the session ID and microservice configuration must be present in order
    // to retrieve raw tiles
    HttpURLConnection conn = null;
    try {
      URL optionsURL = new URL(serverURI.getScheme(), serverURI.getHost(), serverURI.getPort(), "/tile/");
      conn = (HttpURLConnection) optionsURL.openConnection();
      conn.setRequestMethod("OPTIONS");
      conn.connect();
      int response = conn.getResponseCode();
      if (response == HttpURLConnection.HTTP_OK) {
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
          JsonObject root = GsonTools.getInstance().fromJson(reader, JsonObject.class);
          JsonPrimitive provider = root.getAsJsonPrimitive("provider");
          if (provider != null && provider.getAsString().equals("PixelBufferMicroservice")) {
            hasMicroservice = true;
          }
        }
      }
      else {
        throw new IOException("Could not check for OMERO microservice (" + response +")");
      }
    }
    finally {
      conn.disconnect();
    }

    return rtn;
	}

	private int keepAlive() {
		try {
			logger.debug("Attempting to keep connection alive...");
			var pingURL = new URL(serverURI.getScheme(), serverURI.getHost(), serverURI.getPort(), "/webclient/keepalive_ping/?_=" + System.currentTimeMillis());
			HttpURLConnection connection = (HttpURLConnection) pingURL.openConnection();
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestMethod("GET");
			connection.setDoInput(true);
			int response = connection.getResponseCode();
			connection.disconnect();
			return response;
		} catch (IOException e) {
			logger.warn("Error trying to keep connection alive. Client will shut down now.", e.getLocalizedMessage());
			return -1;
		}
	}
	
	/**
	 * Attempt to access the OMERO object given by the provided {@code uri} and {@code type}.
	 * <p>
	 * N.B. being logged on the server doesn't necessarily mean that the user has 
	 * permission to access all the objects on the server.
	 * @param uri
	 * @param type
	 * @return success
	 * @throws IllegalArgumentException
	 * @throws ConnectException 
	 */
	static boolean canBeAccessed(URI uri, OmeroObjectType type) throws IllegalArgumentException, ConnectException {
		try {
			logger.debug("Attempting to access {}...", type.toString().toLowerCase());
			int id = OmeroTools.parseOmeroObjectId(uri, type);
			if (id == -1)
				throw new NullPointerException("No object ID found in: " + uri);
						
			// Implementing this as a switch because of future plates/wells/.. implementations
			String query;
			switch (type) {
			case PROJECT:
			case DATASET:
			case IMAGE:
				query = String.format("/api/v0/m/%s/", type.toURLString());
				break;
			case ORPHANED_FOLDER:
			case UNKNOWN:
				throw new IllegalArgumentException();
			default:
				throw new OperationNotSupportedException("Type not supported: " + type);
			}	
			
			URL url = new URL(uri.getScheme(), uri.getHost(), uri.getPort(), query + id);
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestMethod("GET");
			connection.setDoInput(true);
			int response = connection.getResponseCode();
			connection.disconnect();
			return response == 200;
		} catch (IOException | OperationNotSupportedException ex) {
			logger.warn("Error attempting to access OMERO object", ex.getLocalizedMessage());
			return false;
		}
	}

	
	String getToken() {
		return token;
	}
	
	StringProperty usernameProperty() {
		return username;
	}

	String getUsername() {
		return username.get();
	}

	Group getDefaultGroup() {
		return defaultGroup;
	}
	
	int getUserId() {
		return userId;
	}

  String getSessionId() {
    return sessionId;
  }

  boolean hasMicroservice() {
    return hasMicroservice;
  }

	void setUsername(String newUsername) {
		username.set(newUsername);
	}
	
	/**
	 * Return the server URI ('clean' URI) of this {@code OmeroWebClient}.
	 * @return serverUri
	 * @see OmeroTools#getServerURI(URI)
	 */
	URI getServerURI() {
		return serverURI;
	}
	
	/**
	 * Return an unmodifiable list of all URIs using this {@code OmeroWebClient}. 
	 * @return list of uris
	 * @see #addURI(URI)
	 */
	ObservableList<URI> getURIs() {
		return FXCollections.unmodifiableObservableList(uris);
	}
	
	/**
	 * Add a URI to the list of this client's URIs.
	 * <p>
	 * Note: there is currently no equivalent 'removeURI()' method.
	 * @param uri
	 * @see #getURIs()
	 */
	void addURI(URI uri) {
		Platform.runLater(() -> {
			if (!uris.contains(uri))
				uris.add(uri);
			else
				logger.debug("URI already exists in the list. Ignoring operation.");
		});
	}
	
	/**
	 * Return whether the client is logged in to its server (<b>not</b> necessarily with access to all its images).
	 * 
	 * @return isLoggedIn
	 * @see #checkIfLoggedIn()
	 */
	boolean isLoggedIn() {
		return loggedIn.get();
	}
	
	/**
	 * Return the log property of this client.
	 * @return logProperty
	 */
	BooleanProperty logProperty() {
		return loggedIn;
	}	
	
	/**
	 * Fetch the default {@code Group} from a JSON string (from a login request response).
	 * @param json login request response
	 * @return defaultGroup or {@code null} if none
	 */
	private static Group getDefaultGroup(String json) {
		JsonElement element = JsonParser.parseString(json);
		return GsonTools.getInstance().fromJson(element.getAsJsonObject().get("eventContext"), Group.class);
	}
	
	/**
	 * Fetch the User ID from a JSON string (from a login request response).
	 * @param json
	 * @return userId
	 */
	private static int getUserId(String json) {
		JsonElement element = JsonParser.parseString(json);
		return element.getAsJsonObject().get("eventContext").getAsJsonObject().get("userId").getAsInt();		
	}

	/**
	 * Check and return whether the client is logged in to its server 
	 * (<b>not</b> necessarily with access to all its images).
	 * 
	 * @return isLoggedIn
	 * @see #isLoggedIn()
	 */
	public boolean checkIfLoggedIn() {
		loggedIn.set(OmeroRequests.isLoggedIn(serverURI));
		return loggedIn.get();		
	}
	/**
	 * Log in to the client's server with optional args.
	 * 
	 * @param args
	 * @return success
	 */
	public boolean logIn(String...args) {
		try {
			// TODO: Parse args to look for password (or password file - and don't store them!)
			String usernameOld = username.get();
			char[] password = null;
			List<String> cleanedArgs = new ArrayList<>();
			int i = 0;
			while (i < args.length-1) {
				String name = args[i++];
				if ("--username".equals(name) || "-u".equals(name))
					usernameOld = args[i++];
				else if ("--password".equals(name) || "-p".equals(name)) {
					password = args[i++].toCharArray();
				} else
					cleanedArgs.add(name);
			}
			if (cleanedArgs.size() < args.length)
				args = cleanedArgs.toArray(String[]::new);
			
			PasswordAuthentication authentication;
			if (usernameOld != null && password != null) {
				logger.debug("Username & password parsed from args");
				authentication = new PasswordAuthentication(usernameOld, password);
			} else 
				authentication = OmeroAuthenticatorFX.getPasswordAuthentication("Please enter your login details for OMERO server", serverURI.toString(), usernameOld);
			if (authentication == null)
				return false;
			
			String result = authenticate(authentication, omeroServerInfo.id);
			Arrays.fill(authentication.getPassword(), (char)0);
			
			// Parse login response JSON to get default Group and user ID
			defaultGroup = getDefaultGroup(result);
			userId = getUserId(result);

			// If we have previous URIs and the the username was different
			if (uris.size() > 0 && !usernameOld.isEmpty() && !usernameOld.equals(authentication.getUserName())) {
				Dialogs.showInfoNotification("OMERO login", String.format("OMERO account switched from \"%s\" to \"%s\" for %s", usernameOld, authentication.getUserName(), serverURI));
			} else if (uris.size() == 0 || usernameOld.isEmpty())
				Dialogs.showInfoNotification("OMERO login", String.format("Login successful: %s(\"%s\")", serverURI, authentication.getUserName()));
			
			// If a browser was currently opened with this client, close it
			if (OmeroExtension.getOpenedBrowsers().containsKey(this)) {
				var oldBrowser = OmeroExtension.getOpenedBrowsers().get(this);
				oldBrowser.requestClose();
				OmeroExtension.getOpenedBrowsers().remove(this);
			}
			// (Re)start timer (needed if logging back in for instance)
			startTimer();
			
			// If this method is called from 'project-import' thread (i.e. 'Open URI..'), 'Not on FX Appl. thread' IllegalStateException is thrown
			Platform.runLater(() -> {
				loggedIn.set(true);
				username.set(authentication.getUserName());
			});
			
			logger.debug(result);
			return true;
		} catch (Exception ex) {
			logger.error(ex.getLocalizedMessage());
			Dialogs.showErrorNotification("OMERO web server", "Could not connect to OMERO web server.\nCheck the following:\n- Valid credentials.\n- Access permission.\n- Correct URL.");
		}
		return false;
	}
	
	/**
	 * Log out this client from the server.
	 */
	public void logOut() {
		try {
			URL url = new URL(serverURI.getScheme(), serverURI.getHost(), serverURI.getPort(), "/webclient/logout/");
			HttpURLConnection connection = (HttpURLConnection)url.openConnection();
			connection.setRequestProperty("X-CSRFToken", token);
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("Referer", url + ":" + omeroServerInfo.port);
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setDoInput(true);
			int response = connection.getResponseCode();
			connection.disconnect();
			
			if (response != 200 && response != 403)
				throw new IOException("Server returned " + response);
			
			loggedIn.set(false);
			timer.cancel();
			timer = null;
			username.set("");
			this.token = null;
		} catch (IOException e) {
			logger.error("Could not logout.", e.getLocalizedMessage());
		}
	}
	
	@Override
	public int hashCode() {
		return Objects.hash(serverURI, username);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this)
            return true;
		
		if (!(obj instanceof OmeroWebClient))
			return false;
		
		return serverURI.equals(((OmeroWebClient)obj).getServerURI()) && 
				getUsername().equals(((OmeroWebClient)obj).getUsername());
	}
	
	private boolean loadURLs() throws JsonSyntaxException, MalformedURLException, IOException {
		supportedVersions = parseJSON(OmeroAPI.class, serverURI.toString(), "/api/");
		APIVersion = supportedVersions.getLatestVersion();
		if (APIVersion == null)
			return false;
		omeroURLs = parseJSON(new TypeToken<Map<String, String>>() {}.getType(), APIVersion.versionURL);
		omeroServerInfo = parseJSON(OmeroServerList.class, omeroURLs.get(URL_SERVERS)).data.get(0);
		return true;
	}
	
	private String getCSRFToken() throws JsonSyntaxException, MalformedURLException, IOException {
		var map = parseJSON(Map.class, omeroURLs.get(URL_TOKEN));
		return map.get("data").toString();
	}

	private static String getJSONString(String base, String... query) throws MalformedURLException, IOException {

		StringBuilder sb = new StringBuilder(base);
		for (String q : query)
			sb.append(q);

		HttpURLConnection connection = (HttpURLConnection) URI.create(sb.toString()).toURL().openConnection();
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestMethod("GET");
		connection.setDoInput(true);
		
		// In case of '301 Permanently moved' (sometimes happens when 'http' instead of 'https')
		var code = connection.getResponseCode();
		if (code == 301) {
			var redirectedLink = connection.getHeaderField("Location");
			logger.error("Could not reach {}. Source permanently moved to {}", connection.getURL().toString(), redirectedLink != null ? redirectedLink : "unknown");
			throw new IOException(code + ": " + connection.getResponseMessage());
		}
		
		try (InputStream stream = connection.getInputStream()) {
			return GeneralTools.readInputStreamAsString(stream);
		}
	}

	private static <T> T parseJSON(Class<T> cls, String base, String... query) throws JsonSyntaxException, MalformedURLException, IOException {
		return GsonTools.getInstance().fromJson(getJSONString(base, query), cls);
	}

	@SuppressWarnings("unchecked")
	private static <T> T parseJSON(Type type, String base, String... query) throws JsonSyntaxException, MalformedURLException, IOException {
		return (T) GsonTools.getInstance().fromJson(getJSONString(base, query), type);
	}
	
	private class OmeroAPI {

		private List<OmeroAPIVersion> data;

		OmeroAPIVersion getLatestVersion() {
			if (data == null || data.isEmpty())
				return null;
			return data.get(data.size() - 1);
		}
	}

	private class OmeroAPIVersion {

		@SuppressWarnings("unused")
		private String version;

		@SerializedName("url:base")
		private String versionURL;

	}

	private class OmeroServerList {
		private List<OmeroServerInfo> data;
	}
		

	
	private class OmeroServerInfo {
		
		private int id;
		private String host;
		private int port;
		private String server;
		
		@Override
		public String toString() {
			return String.format("Host: %s, Server: %s, ID: %d, Port: %d", host, server, id, port);
		}
	}
		

	private static class OmeroAuthenticatorFX extends Authenticator {

		private String lastUsername = "";

		@Override
		protected PasswordAuthentication getPasswordAuthentication() {
			PasswordAuthentication authentication = getPasswordAuthentication(getRequestingPrompt(),
					getRequestingHost(), lastUsername);
			if (authentication == null)
				return null;

			lastUsername = authentication.getUserName();
			return authentication;
		}

		static PasswordAuthentication getPasswordAuthentication(String prompt, String host, String lastUsername) {
			GridPane pane = new GridPane();
			Label labHost = new Label(host);
			Label labUsername = new Label("Username");
			TextField tfUsername = new TextField(lastUsername);
			labUsername.setLabelFor(tfUsername);
			
			Label labPassword = new Label("Password");
			PasswordField tfPassword = new PasswordField();
			labPassword.setLabelFor(tfPassword);

			int row = 0;
			if (prompt != null && !prompt.isBlank())
				pane.add(new Label(prompt), 0, row++, 2, 1);
			pane.add(labHost, 0, row++, 2, 1);
			pane.add(labUsername, 0, row);
			pane.add(tfUsername, 1, row++);
			pane.add(labPassword, 0, row);
			pane.add(tfPassword, 1, row++);

			pane.setHgap(5);
			pane.setVgap(5);

			if (!Dialogs.showConfirmDialog("Login", pane))
				return null;

			String userName = tfUsername.getText();
			int passLength = tfPassword.getCharacters().length();
			char[] password = new char[passLength];
			for (int i = 0; i < passLength; i++) {
				password[i] = tfPassword.getCharacters().charAt(i);
			}

			return new PasswordAuthentication(userName, password);
		}
	}
}
