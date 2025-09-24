package com.example.exp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ObjectAnalyzer {

    private static final String TAG = "ObjectAnalyzer";
    private OkHttpClient httpClient;
    private Context appContext;

    // In ObjectAnalyzer.java
    private static final Set<String> GENERIC_TAGS_TO_FILTER = new HashSet<>(Arrays.asList(
            "text", "font", "logo", "brand", "graphics", "illustration", "signage",
            "pattern", "label", "graphic design", "line", "food" // Maybe add "food" or others
    ));
    public interface ObjectAnalysisCallback {
        void onObjectAnalysisSuccess(String description);
        void onObjectAnalysisError(String errorMessage);
    }

    public ObjectAnalyzer(OkHttpClient client, Context context) {
        this.httpClient = client;
        this.appContext = context.getApplicationContext();
    }

    public void analyzeImageDetails(Bitmap bitmap, final ObjectAnalysisCallback callback) {
        if (bitmap == null) {
            callback.onObjectAnalysisError(appContext.getString(R.string.error_object_analysis) + " (Bitmap is null)");
            return;
        }

        Log.d(TAG, "Starting object detail analysis with features: Objects,Color,Tags");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        RequestBody body = RequestBody.create(baos.toByteArray(), MediaType.parse("application/octet-stream"));

        Request request = new Request.Builder()
                .url(AzureConfig.ObjectAndColorAnalysis.getAnalyzeObjectUrl())
                .addHeader("Ocp-Apim-Subscription-Key", AzureConfig.AZURE_VISION_KEY)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Azure Object Analysis API call failed", e);
                callback.onObjectAnalysisError(appContext.getString(R.string.error_object_analysis));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                final String responseBodyString = response.body() != null ? response.body().string() : "null";
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Azure Object Analysis API error: " + response.code() + " Body: " + responseBodyString);
                    callback.onObjectAnalysisError(appContext.getString(R.string.error_object_analysis) + " (Code: " + response.code() + ")");
                    return;
                }

                Log.d(TAG, "Azure Object Analysis API success. Response: " + responseBodyString);
                parseObjectAnalysisResponse(responseBodyString, callback);
            }
        });
    }

    private void parseObjectAnalysisResponse(String jsonResponse, ObjectAnalysisCallback callback) {
        String detectedObjectName = null;
        List<String> dominantColors = new ArrayList<>();
        List<String> potentialTexts = new ArrayList<>();

        try {
            JSONObject root = new JSONObject(jsonResponse);

            if (root.has("objects")) {
                JSONArray objectsArray = root.getJSONArray("objects");
                if (objectsArray.length() > 0) {
                    JSONObject firstObject = objectsArray.getJSONObject(0);
                    if (firstObject.has("object")) {
                        detectedObjectName = firstObject.getString("object");
                    }
                }
            }

            if (root.has("color")) {
                JSONObject colorObject = root.getJSONObject("color");
                if (colorObject.has("dominantColors")) {
                    JSONArray colorsArray = colorObject.getJSONArray("dominantColors");
                    for (int i = 0; i < colorsArray.length(); i++) {
                        dominantColors.add(colorsArray.getString(i));
                    }
                }
            }

            Set<String> lowerCaseDominantColors = new HashSet<>();
            for (String color : dominantColors) {
                lowerCaseDominantColors.add(color.toLowerCase(Locale.ROOT));
            }

            if (root.has("tags")) {
                JSONArray tagsArray = root.getJSONArray("tags");
                List<String> contentLikeTags = new ArrayList<>();

                for (int i = 0; i < tagsArray.length(); i++) {
                    JSONObject tagObject = tagsArray.getJSONObject(i);
                    if (tagObject.has("name")) {
                        String tagName = tagObject.getString("name");
                        String lowerTagName = tagName.toLowerCase(Locale.ROOT);

                        if (lowerCaseDominantColors.contains(lowerTagName)) {
                            continue; // Skip if the tag is primarily a color already listed
                        }
                        if (GENERIC_TAGS_TO_FILTER.contains(lowerTagName)){
                            continue; // Skip generic tags
                        }
                        // Add if it seems like content (has a space, or is reasonably long, or just not filtered)
                        if (tagName.contains(" ") || tagName.length() > 4) { 
                            contentLikeTags.add(tagName);
                        } else if (!GENERIC_TAGS_TO_FILTER.contains(lowerTagName)) {
                            // Shorter tags that are not explicitly generic might still be useful
                            contentLikeTags.add(tagName);
                        }
                    }
                }
                potentialTexts.addAll(contentLikeTags);
            }

            String resultDescription = constructDescription(detectedObjectName, dominantColors, potentialTexts);
            callback.onObjectAnalysisSuccess(resultDescription);

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing Object Analysis JSON response", e);
            callback.onObjectAnalysisError(appContext.getString(R.string.error_object_analysis) + " (JSON Parse Fail)");
        }
    }

    private String constructDescription(String objectName, List<String> colors, List<String> texts) {
        StringBuilder sb = new StringBuilder();
        boolean objectActuallyFound = objectName != null && !objectName.isEmpty() && !"text".equalsIgnoreCase(objectName);
        boolean objectIsJustTextFromDetection = "text".equalsIgnoreCase(objectName);
        boolean colorsFound = !colors.isEmpty();
        boolean textsFound = !texts.isEmpty();

        String limitedTextsString = textsFound ? limitText(String.join("; ", texts), 5) : "";

        if (objectActuallyFound) {
            String formattedObjectName = objectName.substring(0, 1).toUpperCase(Locale.ROOT) + objectName.substring(1);
            if (colorsFound && textsFound) {
                sb.append(String.format(appContext.getString(R.string.object_analysis_success_full),
                        formattedObjectName, String.join(", ", colors), limitedTextsString));
            } else if (colorsFound) {
                sb.append(String.format(appContext.getString(R.string.object_analysis_success_object_color),
                        formattedObjectName, String.join(", ", colors)));
            } else if (textsFound) {
                sb.append(String.format(appContext.getString(R.string.object_analysis_success_object_text),
                        formattedObjectName, limitedTextsString));
            } else {
                sb.append(String.format(appContext.getString(R.string.object_analysis_success_object_only),
                        formattedObjectName));
            }
        } else { // No specific object found, or object detected was just "text"
            if (textsFound) {
                // If object was "text" or not found, but we have descriptive text from tags
                sb.append("I found an item with the text '").append(limitedTextsString).append("'.");
                if (colorsFound) {
                    sb.append(" The main colors are ").append(String.join(", ", colors)).append(".");
                }
            } else if (colorsFound) {
                // No object, no specific text, but colors found
                sb.append(String.format(appContext.getString(R.string.object_analysis_no_object_color_only),
                        String.join(", ", colors)));
            } else if (objectIsJustTextFromDetection) {
                 // Object detected as "text", but no other useful text from tags, no colors
                 sb.append("I see some text, but can't make out specific details.");
            } else {
                // Nothing clearly identifiable
                sb.append(appContext.getString(R.string.object_analysis_nothing_clear));
            }
        }

        String finalDescription = sb.toString().trim();
        if (finalDescription.isEmpty()) {
             return appContext.getString(R.string.object_analysis_nothing_clear);
        }
        return finalDescription;
    }

    private String limitText(String text, int maxWords) {
        if (text == null || text.isEmpty()) return appContext.getString(R.string.no_text_found);

        List<String> distinctPhrases = Arrays.stream(text.split(";"))
                                          .map(String::trim)
                                          .filter(s -> !s.isEmpty() && s.matches(".*[a-zA-Z0-9].*"))
                                          .distinct()
                                          .collect(Collectors.toList());

        if (distinctPhrases.isEmpty()) return appContext.getString(R.string.no_text_found);

        List<String> wordsToSpeak = new ArrayList<>();
        int currentWordCount = 0;
        for (String phrase : distinctPhrases) {
            if (currentWordCount >= maxWords) break;
            // Add the phrase as a whole if it doesn't push way over the limit, or split if necessary
            // This simple version just adds the phrase if it fits, might need more complex word-by-word addition
            if (currentWordCount + phrase.split("\\s+").length <= maxWords + 2 || distinctPhrases.size() ==1 ) { // allow some overflow for last phrase
                wordsToSpeak.add(phrase);
                currentWordCount += phrase.split("\\s+").length;
            } else {
                 String[] wordsInPhrase = phrase.split("\\s+");
                 for(String w : wordsInPhrase) {
                     if (currentWordCount < maxWords) {
                         wordsToSpeak.add(w);
                         currentWordCount++;
                     } else break;
                 }
            }
        }
        
        if(wordsToSpeak.isEmpty()) return appContext.getString(R.string.no_text_found);

        String result = String.join(", ", wordsToSpeak);
        if (currentWordCount >= maxWords && distinctPhrases.size() > wordsToSpeak.size()){ // Check if actual words from original text exceeded limit
             result += "...";
        }
        return result;
    }
}
