package com.bettercloud.vault.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.VaultException;
import com.bettercloud.vault.json.Json;
import com.bettercloud.vault.json.JsonArray;
import com.bettercloud.vault.json.JsonObject;
import com.bettercloud.vault.response.LogicalResponse;
import com.bettercloud.vault.rest.Rest;
import com.bettercloud.vault.rest.RestException;
import com.bettercloud.vault.rest.RestResponse;

/**
 * <p>The implementing class for Vault's core/logical operations (e.g. read, write).</p>
 *
 * <p>This class is not intended to be constructed directly.  Rather, it is meant to used by way of <code>Vault</code>
 * in a DSL-style builder pattern.  See the Javadoc comments of each <code>public</code> method for usage examples.</p>
 */
public class Logical {

    private final VaultConfig config;

    public Logical(final VaultConfig config) {
        this.config = config;
    }

    /**
     * <p>Basic read operation to retrieve a secret.  A single secret key can map to multiple name-value pairs,
     * which can be retrieved from the response object.  E.g.:</p>
     *
     * <blockquote>
     * <pre>{@code
     * final LogicalResponse response = vault.logical().read("secret/hello");
     *
     * final String value = response.getData().get("value");
     * final String otherValue = response.getData().get("other_value");
     * }</pre>
     * </blockquote>
     *
     * @param path The Vault key value from which to read (e.g. <code>secret/hello</code>)
     * @return The response information returned from Vault
     * @throws VaultException If any errors occurs with the REST request (e.g. non-200 status code, invalid JSON payload, etc), and the maximum number of retries is exceeded.
     */
    public LogicalResponse read(final String path) throws VaultException {
        int retryCount = 0;
        while (true) {
            try {
                // Make an HTTP request to Vault
                final RestResponse restResponse = new Rest()//NOPMD
                        .url(config.getAddress() + "/v1/" + path)
                        .header("X-Vault-Token", config.getToken())
                        .connectTimeoutSeconds(config.getOpenTimeout())
                        .readTimeoutSeconds(config.getReadTimeout())
                        .sslPemUTF8(config.getSslPemUTF8())
                        .sslVerification(config.isSslVerify() != null ? config.isSslVerify() : null)
                        .get();

                // Validate response
                if (restResponse.getStatus() != 200) {
                    throw new VaultException("Vault responded with HTTP status code: " + restResponse.getStatus(), restResponse.getStatus());
                }

                return new LogicalResponse(restResponse, retryCount);
            } catch (RuntimeException | VaultException | RestException e) {
                // If there are retries to perform, then pause for the configured interval and then execute the loop again...
                if (retryCount < config.getMaxRetries()) {
                    retryCount++;
                    try {
                        final int retryIntervalMilliseconds = config.getRetryIntervalMilliseconds();
                        Thread.sleep(retryIntervalMilliseconds);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                } else if (e instanceof VaultException) {
                    // ... otherwise, give up.
                    throw (VaultException) e;
                } else {
                    throw new VaultException(e);
                }
            }
        }
    }

    /**
     * <p>Basic operation to store secrets.  Multiple name value pairs can be stored under the same secret key.
     * E.g.:</p>
     *
     * <blockquote>
     * <pre>{@code
     * final Map<String, String> nameValuePairs = new HashMap<String, String>();
     * nameValuePairs.put("value", "foo");
     * nameValuePairs.put("other_value", "bar");
     *
     * final LogicalResponse response = vault.logical().write("secret/hello", nameValuePairs);
     * }</pre>
     * </blockquote>
     *
     * @param path The Vault key value to which to write (e.g. <code>secret/hello</code>)
     * @param nameValuePairs Secret name and value pairs to store under this Vault key (can be <code>null</code> for writing to keys that do not need or expect any fields to be specified)
     * @return The response information received from Vault
     * @throws VaultException If any errors occurs with the REST request, and the maximum number of retries is exceeded.
     */
    public LogicalResponse write(final String path, final Map<String, String> nameValuePairs) throws VaultException {
        int retryCount = 0;
        while (true) {
            try {
                JsonObject requestJson = Json.object();
                if (nameValuePairs != null) {
                    for (final Map.Entry<String, String> pair : nameValuePairs.entrySet()) {
                        requestJson = requestJson.add(pair.getKey(), pair.getValue());
                    }
                }

                final RestResponse restResponse = new Rest()//NOPMD
                        .url(config.getAddress() + "/v1/" + path)
                        .body(requestJson.toString().getBytes("UTF-8"))
                        .header("X-Vault-Token", config.getToken())
                        .connectTimeoutSeconds(config.getOpenTimeout())
                        .readTimeoutSeconds(config.getReadTimeout())
                        .sslPemUTF8(config.getSslPemUTF8())
                        .sslVerification(config.isSslVerify() != null ? config.isSslVerify() : null)
                        .post();

                // HTTP Status should be either 200 (with content - e.g. PKI write) or 204 (no content)
                final int restStatus = restResponse.getStatus();
                if (restStatus == 200 || restStatus == 204) {
                    return new LogicalResponse(restResponse, retryCount);
                } else {
                    throw new VaultException("Expecting HTTP status 204 or 200, but instead receiving " + restStatus, restStatus);
                }
            } catch (Exception e) {
                // If there are retries to perform, then pause for the configured interval and then execute the loop again...
                if (retryCount < config.getMaxRetries()) {
                    retryCount++;
                    try {
                        final int retryIntervalMilliseconds = config.getRetryIntervalMilliseconds();
                        Thread.sleep(retryIntervalMilliseconds);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                } else if (e instanceof VaultException) {
                    // ... otherwise, give up.
                    throw (VaultException) e;
                } else {
                    throw new VaultException(e);
                }
            }
        }
    }

    /**
     * <p>Retrieve a list of keys corresponding to key/value pairs at a given Vault path.</p>
     *
     * <p>Key values ending with a trailing-slash characters are sub-paths.  Running a subsequent <code>list()</code>
     * call, using the original path appended with this key, will retrieve all secret keys stored at that sub-path.</p>
     *
     * <p>This method returns only the secret keys, not values.  To retrieve the actual stored value for a key,
     * use <code>read()</code> with the key appended onto the original base path.</p>
     *
     * @param path The Vault key value at which to look for secrets (e.g. <code>secret</code>)
     * @return A list of keys corresponding to key/value pairs at a given Vault path, or an empty list if there are none
     * @throws VaultException If any errors occur, or unexpected response received from Vault
     */
    public List<String> list(final String path) throws VaultException {
        final String fullPath = path == null ? "list=true" : path + "?list=true";
        LogicalResponse response = null;
        try {
            response = read(fullPath);
        } catch (final VaultException e) {
            if (e.getHttpStatusCode() != 404) {
                throw e;
            }
        }

        final List<String> returnValues = new ArrayList<>();
        if (
                response != null
                && response.getRestResponse().getStatus() != 404
                && response.getData() != null
                && response.getData().get("keys") != null
        ) {

            final JsonArray keys = Json.parse(response.getData().get("keys")).asArray();
            for (int index = 0; index < keys.size(); index++) {
                returnValues.add(keys.get(index).asString());
            }
        }
        return returnValues;
    }

    /**
     * <p>Deletes the key/value pair located at the provided path.</p>
     *
     * <p>If the path represents a sub-path, then all of its contents must be deleted prior to deleting the empty
     * sub-path itself.</p>
     *
     * @param path The Vault key value to delete (e.g. <code>secret/hello</code>).
     * @return The response information received from Vault
     * @throws VaultException If any error occurs, or unexpected response received from Vault
     */
    public LogicalResponse delete(final String path) throws VaultException {
        int retryCount = 0;
        while (true) {
            try {
                // Make an HTTP request to Vault
                final RestResponse restResponse = new Rest()//NOPMD
                        .url(config.getAddress() + "/v1/" + path)
                        .header("X-Vault-Token", config.getToken())
                        .connectTimeoutSeconds(config.getOpenTimeout())
                        .readTimeoutSeconds(config.getReadTimeout())
                        .sslPemUTF8(config.getSslPemUTF8())
                        .sslVerification(config.isSslVerify() != null ? config.isSslVerify() : null)
                        .delete();

                // Validate response
                if (restResponse.getStatus() != 204) {
                    throw new VaultException("Vault responded with HTTP status code: " + restResponse.getStatus(), restResponse.getStatus());
                }
                return new LogicalResponse(restResponse, retryCount);
            } catch (RuntimeException | VaultException | RestException e) {
                // If there are retries to perform, then pause for the configured interval and then execute the loop again...
                if (retryCount < config.getMaxRetries()) {
                    retryCount++;
                    try {
                        final int retryIntervalMilliseconds = config.getRetryIntervalMilliseconds();
                        Thread.sleep(retryIntervalMilliseconds);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                } else if (e instanceof VaultException) {
                    // ... otherwise, give up.
                    throw (VaultException) e;
                } else {
                    throw new VaultException(e);
                }
            }
        }
    }
}
