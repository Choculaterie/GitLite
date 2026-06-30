package com.choculaterie.gitlite.network;

import com.choculaterie.gitlite.config.GitLiteSettings;
import com.choculaterie.gitlite.model.CreateBranchRequest;
import com.choculaterie.gitlite.model.CreateRepoRequest;
import com.choculaterie.gitlite.model.GitBranchDto;
import com.choculaterie.gitlite.model.GitCommitDto;
import com.choculaterie.gitlite.model.GitCommitListResponse;
import com.choculaterie.gitlite.model.GitModuleListResponse;
import com.choculaterie.gitlite.model.GitReleaseDto;
import com.choculaterie.gitlite.model.GitReleaseListResponse;
import com.choculaterie.gitlite.model.GitRepoDetailDto;
import com.choculaterie.gitlite.model.GitRepoDto;
import com.choculaterie.gitlite.model.ImportModuleRequest;
import com.choculaterie.gitlite.model.SaveZoneRequest;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Static HTTP client for the GitLite backend API ({@code /api/GitLiteModAPI}).
 *
 * <p>Every method dispatches its HTTP call on a ForkJoinPool thread via
 * {@link CompletableFuture#supplyAsync} and deserialises the JSON response with
 * {@link Gson}. All requests are authenticated with the API key from
 * {@link GitLiteSettings} via the {@code X-API-Key} header.
 *
 * <p>Two timeouts are applied: {@link #TIMEOUT_STANDARD} (10 s) for ordinary
 * requests and {@link #TIMEOUT_UPLOAD} (30 s) for file-upload and download
 * operations that may transfer several megabytes.
 *
 * <p>Error responses are converted to {@link IOException}s using
 * {@link #extractError}, which attempts to parse a human-readable message from
 * the JSON body before falling back to a generic status-code message. Callers
 * should handle errors in the {@code exceptionally} stage of the returned future.
 */
public class GitLiteNetworkManager {

	private static final String BASE_URL = "https://api.choculaterie.com/api/GitLiteModAPI";
	private static final Gson GSON = new Gson();

	private static final int TIMEOUT_STANDARD = 10_000; // ms
	private static final int TIMEOUT_UPLOAD   = 30_000; // ms - used for commits and downloads

	/** MIME boundary token for multipart/form-data uploads. */
	private static final String BOUNDARY = "----GitLiteBoundary" + System.currentTimeMillis();

	// -------------------------------------------------------------------------
	// Public API - repositories
	// -------------------------------------------------------------------------

	/**
	 * Returns all repositories visible to the authenticated user.
	 *
	 * @return a future resolving to the list of repository summaries
	 */
	public static CompletableFuture<List<GitRepoDto>> listRepos() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String response = request("GET", BASE_URL + "/repos", null, null);
				Type listType = new TypeToken<List<GitRepoDto>>() {}.getType();
				return GSON.<List<GitRepoDto>>fromJson(response, listType);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	/**
	 * Fetches the full detail for a single repository, including its branch list.
	 *
	 * @param repoId the repository's unique identifier
	 * @return a future resolving to the repository detail DTO
	 */
	public static CompletableFuture<GitRepoDetailDto> getRepo(String repoId) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String response = request("GET", BASE_URL + "/repos/" + repoId, null, null);
				return GSON.fromJson(response, GitRepoDetailDto.class);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	/**
	 * Creates a new repository owned by the authenticated user.
	 *
	 * @param name        repository name (must be unique for the user)
	 * @param description optional description; may be empty
	 * @return a future resolving to the newly-created repository detail
	 */
	public static CompletableFuture<GitRepoDetailDto> createRepo(String name, String description) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				byte[] body = GSON.toJson(new CreateRepoRequest(name, description)).getBytes(StandardCharsets.UTF_8);
				String response = request("POST", BASE_URL + "/repos", "application/json", body);
				return GSON.fromJson(response, GitRepoDetailDto.class);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	/**
	 * Saves (or replaces) the repository's persistent capture zone in the cloud, so it survives
	 * across game installs and is available to anyone the repository is shared with.
	 *
	 * @param repoId repository identifier
	 * @param x1     first corner X
	 * @param y1     first corner Y
	 * @param z1     first corner Z
	 * @param x2     second corner X
	 * @param y2     second corner Y
	 * @param z2     second corner Z
	 * @return a future that completes once the zone is saved
	 */
	public static CompletableFuture<Void> saveZone(String repoId, int x1, int y1, int z1, int x2, int y2, int z2) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				byte[] body = GSON.toJson(new SaveZoneRequest(x1, y1, z1, x2, y2, z2)).getBytes(StandardCharsets.UTF_8);
				request("PUT", BASE_URL + "/repos/" + repoId + "/zone", "application/json", body);
				return null;
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	// -------------------------------------------------------------------------
	// Public API - commits
	// -------------------------------------------------------------------------

	/**
	 * Returns a paginated list of commits on a branch.
	 *
	 * @param repoId     repository identifier
	 * @param branchName branch name
	 * @param page       1-based page number
	 * @param pageSize   number of commits per page
	 * @return a future resolving to the paginated commit response
	 */
	public static CompletableFuture<GitCommitListResponse> listCommits(String repoId, String branchName, int page, int pageSize) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String url = BASE_URL + "/repos/" + repoId + "/branches/" + branchName + "/commits"
						+ "?page=" + page + "&pageSize=" + pageSize;
				String response = request("GET", url, null, null);
				return GSON.fromJson(response, GitCommitListResponse.class);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	/**
	 * Uploads a file as a new commit on the specified branch.
	 *
	 * <p>The upload uses {@code multipart/form-data} encoding (see
	 * {@link #uploadCommit}).
	 *
	 * @param repoId     repository identifier
	 * @param branchName target branch
	 * @param file       file to upload (read in its entirety; must fit in memory)
	 * @param message    commit message
	 * @return a future resolving to the created commit DTO
	 */
	public static CompletableFuture<GitCommitDto> pushCommit(String repoId, String branchName, File file, String message) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				byte[] fileBytes = Files.readAllBytes(file.toPath());
				String response = uploadCommit(repoId, branchName, file.getName(), fileBytes, message);
				return GSON.fromJson(response, GitCommitDto.class);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	/**
	 * Downloads the raw {@code .litematic} bytes for a commit (e.g. to revert a zone to it
	 * locally, without pushing).
	 *
	 * @param commitId the commit's unique identifier
	 * @return a future resolving to the raw file bytes
	 */
	public static CompletableFuture<byte[]> downloadCommit(String commitId) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return downloadBytes(BASE_URL + "/commits/" + commitId + "/download");
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	// -------------------------------------------------------------------------
	// Public API - modules
	// -------------------------------------------------------------------------

	/**
	 * Returns a paginated list of publicly-available modules.
	 *
	 * @param page     1-based page number
	 * @param pageSize number of items per page
	 * @return a future resolving to the paginated module list response
	 */
	public static CompletableFuture<GitModuleListResponse> listModules(int page, int pageSize) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String url = BASE_URL + "/modules?page=" + page + "&pageSize=" + pageSize;
				String response = request("GET", url, null, null);
				return GSON.fromJson(response, GitModuleListResponse.class);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	/**
	 * Downloads the raw file bytes for a module.
	 *
	 * @param moduleId the module's unique identifier
	 * @return a future resolving to the raw file bytes
	 */
	public static CompletableFuture<byte[]> downloadModuleFile(String moduleId) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return downloadBytes(BASE_URL + "/modules/" + moduleId + "/download");
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	/**
	 * Imports a module from the public module library as a new commit on a branch.
	 *
	 * @param repoId     target repository identifier
	 * @param branchName target branch name
	 * @param moduleId   identifier of the module to import
	 * @param message    commit message for the import
	 * @return a future resolving to the created commit DTO
	 */
	public static CompletableFuture<GitCommitDto> importModule(String repoId, String branchName, String moduleId, String message) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				byte[] body = GSON.toJson(new ImportModuleRequest(moduleId, message)).getBytes(StandardCharsets.UTF_8);
				String response = request("POST",
						BASE_URL + "/repos/" + repoId + "/branches/" + branchName + "/import-module",
						"application/json", body);
				return GSON.fromJson(response, GitCommitDto.class);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	// -------------------------------------------------------------------------
	// Public API - branches
	// -------------------------------------------------------------------------

	/**
	 * Creates a new branch in the specified repository.
	 *
	 * @param repoId     repository identifier
	 * @param name       name of the new branch
	 * @param fromBranch name of the branch to branch from
	 * @return a future resolving to the created branch DTO
	 */
	public static CompletableFuture<GitBranchDto> createBranch(String repoId, String name, String fromBranch) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				byte[] body = GSON.toJson(new CreateBranchRequest(name, fromBranch)).getBytes(StandardCharsets.UTF_8);
				String response = request("POST", BASE_URL + "/repos/" + repoId + "/branches",
						"application/json", body);
				return GSON.fromJson(response, GitBranchDto.class);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	// -------------------------------------------------------------------------
	// Public API - releases
	// -------------------------------------------------------------------------

	/**
	 * Publishes a commit as a tagged release: a real, public Schematic created from that commit's blob.
	 *
	 * @param repoId        repository identifier
	 * @param commitId      identifier of the commit to release
	 * @param tagName       version tag (e.g. "v1.0")
	 * @param name          optional name override; defaults to the repo's own name server-side
	 * @param description   optional description override; defaults to the repo's own description server-side
	 * @param thumbnailFile required thumbnail image file
	 * @return a future resolving to the created release DTO
	 */
	public static CompletableFuture<GitReleaseDto> createRelease(String repoId, String commitId, String tagName,
			String name, String description, File thumbnailFile) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				byte[] thumbnailBytes = Files.readAllBytes(thumbnailFile.toPath());
				String response = uploadRelease(repoId, commitId, tagName, name, description,
						thumbnailFile.getName(), thumbnailBytes);
				return GSON.fromJson(response, GitReleaseDto.class);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	/**
	 * Returns a paginated list of a repository's releases.
	 *
	 * @param repoId   repository identifier
	 * @param page     1-based page number
	 * @param pageSize number of releases per page
	 * @return a future resolving to the paginated release response
	 */
	public static CompletableFuture<GitReleaseListResponse> listReleases(String repoId, int page, int pageSize) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String url = BASE_URL + "/repos/" + repoId + "/releases?page=" + page + "&pageSize=" + pageSize;
				String response = request("GET", url, null, null);
				return GSON.fromJson(response, GitReleaseListResponse.class);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	// -------------------------------------------------------------------------
	// Private helpers - HTTP
	// -------------------------------------------------------------------------

	/**
	 * Sends a standard (non-multipart) HTTP request.
	 *
	 * @param method      HTTP verb (e.g. {@code "GET"}, {@code "POST"})
	 * @param urlStr      fully-qualified URL string
	 * @param contentType MIME type for the request body, or {@code null} for no body
	 * @param body        raw request body bytes, or {@code null}
	 * @return the response body as a UTF-8 string
	 * @throws IOException if the request fails or returns an error status
	 */
	private static String request(String method, String urlStr, String contentType, byte[] body) throws IOException {
		URL url = URI.create(urlStr).toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		try {
			connection.setRequestMethod(method);
			connection.setConnectTimeout(TIMEOUT_STANDARD);
			connection.setReadTimeout(TIMEOUT_STANDARD);
			connection.setRequestProperty("X-API-Key", GitLiteSettings.getInstance().getApiKey());

			if (contentType != null) {
				connection.setRequestProperty("Content-Type", contentType);
			}

			if (body != null) {
				connection.setDoOutput(true);
				try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
					out.write(body);
				}
			}

			int code = connection.getResponseCode();
			// Use the error stream for 4xx/5xx so we can read the server's error message.
			InputStream stream = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
			String response = readStream(stream);

			if (code < 200 || code >= 300) {
				throw new IOException(extractError(response, code));
			}

			return response;
		} finally {
			connection.disconnect();
		}
	}

	/**
	 * Sends a commit-upload request using {@code multipart/form-data} encoding.
	 *
	 * @param repoId     repository identifier
	 * @param branchName target branch
	 * @param fileName   original filename included in the form part headers
	 * @param fileBytes  raw file content
	 * @param message    commit message
	 * @return the response body as a UTF-8 string
	 * @throws IOException if the request fails or returns an error status
	 */
	private static String uploadCommit(String repoId, String branchName, String fileName, byte[] fileBytes, String message) throws IOException {
		String urlStr = BASE_URL + "/repos/" + repoId + "/branches/" + branchName + "/commits";
		URL url = URI.create(urlStr).toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		try {
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(TIMEOUT_UPLOAD);
			connection.setReadTimeout(TIMEOUT_UPLOAD);
			connection.setRequestProperty("X-API-Key", GitLiteSettings.getInstance().getApiKey());
			connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);

			try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
				writeFormField(out, "Message", message);
				writeFormField(out, "FileName", fileName);
				writeFormFile(out, "File", fileName, fileBytes);
				out.writeBytes("--" + BOUNDARY + "--\r\n");
			}

			int code = connection.getResponseCode();
			InputStream stream = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
			String response = readStream(stream);

			if (code < 200 || code >= 300) {
				throw new IOException(extractError(response, code));
			}

			return response;
		} finally {
			connection.disconnect();
		}
	}

	/**
	 * Sends a release-creation request using {@code multipart/form-data} encoding.
	 *
	 * @param repoId             repository identifier
	 * @param commitId           identifier of the commit to release
	 * @param tagName            version tag
	 * @param name               optional name override, or {@code null}
	 * @param description        optional description override, or {@code null}
	 * @param thumbnailFileName  original thumbnail filename included in the form part headers
	 * @param thumbnailBytes     raw thumbnail image content
	 * @return the response body as a UTF-8 string
	 * @throws IOException if the request fails or returns an error status
	 */
	private static String uploadRelease(String repoId, String commitId, String tagName, String name, String description,
			String thumbnailFileName, byte[] thumbnailBytes) throws IOException {
		String urlStr = BASE_URL + "/repos/" + repoId + "/releases";
		URL url = URI.create(urlStr).toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		try {
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(TIMEOUT_UPLOAD);
			connection.setReadTimeout(TIMEOUT_UPLOAD);
			connection.setRequestProperty("X-API-Key", GitLiteSettings.getInstance().getApiKey());
			connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);

			try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
				writeFormField(out, "CommitId", commitId);
				writeFormField(out, "TagName", tagName);
				if (name != null && !name.isEmpty()) writeFormField(out, "Name", name);
				if (description != null && !description.isEmpty()) writeFormField(out, "Description", description);
				writeFormFile(out, "Thumbnail", thumbnailFileName, thumbnailBytes);
				out.writeBytes("--" + BOUNDARY + "--\r\n");
			}

			int code = connection.getResponseCode();
			InputStream stream = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
			String response = readStream(stream);

			if (code < 200 || code >= 300) {
				throw new IOException(extractError(response, code));
			}

			return response;
		} finally {
			connection.disconnect();
		}
	}

	/**
	 * Downloads a binary resource and returns it as a byte array.
	 *
	 * @param urlStr fully-qualified URL of the resource
	 * @return raw content bytes
	 * @throws IOException if the request fails or returns an error status
	 */
	private static byte[] downloadBytes(String urlStr) throws IOException {
		URL url = URI.create(urlStr).toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		try {
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(TIMEOUT_UPLOAD);
			connection.setReadTimeout(TIMEOUT_UPLOAD);
			connection.setRequestProperty("X-API-Key", GitLiteSettings.getInstance().getApiKey());

			int code = connection.getResponseCode();
			if (code < 200 || code >= 300) {
				String error = readStream(connection.getErrorStream());
				throw new IOException(extractError(error, code));
			}
			try (InputStream in = connection.getInputStream();
				 java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
				byte[] chunk = new byte[8192];
				int n;
				while ((n = in.read(chunk)) != -1) {
					buffer.write(chunk, 0, n);
				}
				return buffer.toByteArray();
			}
		} finally {
			connection.disconnect();
		}
	}

	// -------------------------------------------------------------------------
	// Private helpers - multipart encoding
	// -------------------------------------------------------------------------

	/** Writes a plain text form field to a multipart body. */
	private static void writeFormField(DataOutputStream out, String name, String value) throws IOException {
		out.writeBytes("--" + BOUNDARY + "\r\n");
		out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
		out.write(value.getBytes(StandardCharsets.UTF_8));
		out.writeBytes("\r\n");
	}

	/** Writes a binary file part to a multipart body. */
	private static void writeFormFile(DataOutputStream out, String name, String fileName, byte[] data) throws IOException {
		out.writeBytes("--" + BOUNDARY + "\r\n");
		out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n");
		out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
		out.write(data);
		out.writeBytes("\r\n");
	}

	// -------------------------------------------------------------------------
	// Private helpers - response parsing
	// -------------------------------------------------------------------------

	/** Reads an {@link InputStream} fully into a UTF-8 string, returning {@code ""} for null streams. */
	private static String readStream(InputStream stream) throws IOException {
		if (stream == null) {
			return "";
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			StringBuilder result = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				result.append(line);
			}
			return result.toString();
		} finally {
			stream.close();
		}
	}

	/**
	 * Attempts to extract a human-readable error message from a JSON response body.
	 * Falls back to a generic "Request failed" message if the body is not valid JSON
	 * or does not contain an {@code "error"} or {@code "message"} field.
	 *
	 * @param response raw response body string
	 * @param code     HTTP status code
	 * @return a non-null error message string
	 */
	private static String extractError(String response, int code) {
		try {
			JsonObject json = JsonParser.parseString(response).getAsJsonObject();
			String message = null;
			if (json.has("error")) {
				message = json.get("error").getAsString();
			} else if (json.has("message")) {
				message = json.get("message").getAsString();
			}
			if (message != null) {
				if (json.has("detail") && !json.get("detail").isJsonNull()) {
					message += ": " + json.get("detail").getAsString();
				}
				return message;
			}
		} catch (Exception ignored) {
			// response wasn't JSON; fall through to generic message
		}
		return "Request failed with status " + code;
	}
}
