package com.example.exp3;

public class AzureConfig {

    // Azure Computer Vision Configuration
    public static final String AZURE_VISION_KEY = "FN29Q0aST509xP4bbJzZerbnli3FNeO5BQwYVlU8lSAmxYLWQcl4JQQJ99BIACGhslBXJ3w3AAAEACOGNk4i";
    public static final String AZURE_VISION_ENDPOINT = "https://ocrspeech3.cognitiveservices.azure.com";
    public static final String AZURE_VISION_API_VERSION_READ = "3.2"; // For Read API

    // Azure Maps Configuration
    public static final String AZURE_MAPS_KEY = "4pR7AM2Pfy8UA9PQUUJm8iqLC0ntdfGbnwJ4Tec4Iu3jP5w1GoiTJQQJ99BIAC5RqLJF8uudAAAgAZMPhNLk"; 
    public static final String AZURE_MAPS_BASE_URL = "https://atlas.microsoft.com";

    // Azure Speech Services Configuration
    public static final String AZURE_SPEECH_KEY = "FN29Q0aST509xP4bbJzZerbnli3FNeO5BQwYVlU8lSAmxYLWQcl4JQQJ99BIACGhslBXJ3w3AAAEACOGNk4i";
    public static final String AZURE_SPEECH_REGION = "centralindia";
    public static final String AZURE_SPEECH_ENDPOINT = "https://centralindia.tts.speech.microsoft.com";

    // API Timeouts (in seconds)
    public static final int CONNECT_TIMEOUT = 30;
    public static final int READ_TIMEOUT = 30;
    public static final int WRITE_TIMEOUT = 30;

    // --- Read API (Advanced OCR) Configuration ---
    public static final class ReadAPI {
        // Removed LANGUAGE_HINT as we are enabling auto-detection by removing the language parameter
        public static final int POLLING_INITIAL_DELAY_MS = 3000;
        public static final int POLLING_INTERVAL_MS = 2000;
        public static final int MAX_POLLING_ATTEMPTS = 15;

        public static String getReadAnalyzeUrl() {
            // Omit the language parameter to enable auto-detection by Azure Read API
            return AZURE_VISION_ENDPOINT + "/vision/v" + AZURE_VISION_API_VERSION_READ + "/read/analyze";
        }
    }

    // --- Image Analysis (Scene Description & Objects) v3.2 Configuration ---
    public static final class AnalyzeScene {
        public static final String API_VERSION = "3.2"; 
        public static final String FEATURES = "Description,Objects"; 
        public static final String LANGUAGE = "en"; 

        public static String getAnalyzeSceneUrl() {
            return AZURE_VISION_ENDPOINT + "/vision/v" + API_VERSION + "/analyze?visualFeatures=" + FEATURES + "&language=" + LANGUAGE;
        }
    }

    // --- Image Analysis (Object, Color, Tags for text) v3.2 Configuration ---
    public static final class ObjectAndColorAnalysis {
        public static final String API_VERSION = "3.2"; 
        public static final String FEATURES = "Objects,Color,Tags";
        public static final String LANGUAGE = "en"; 

        public static String getAnalyzeObjectUrl() {
            return AZURE_VISION_ENDPOINT + "/vision/v" + API_VERSION + "/analyze?visualFeatures=" + FEATURES + "&language=" + LANGUAGE;
        }
    }

    // Maps Configuration
    public static final class Maps {
        public static final String API_VERSION = "1.0";
        public static final int SEARCH_RADIUS_METERS = 5000;

        public static String getReverseGeocodingUrl(double latitude, double longitude) {
            return AZURE_MAPS_BASE_URL + "/search/address/reverse/json" +
                    "?api-version=" + API_VERSION +
                    "&subscription-key=" + AZURE_MAPS_KEY +
                    "&query=" + latitude + "," + longitude;
        }

        public static String getNearbySearchPoiUrl(double latitude, double longitude, String categoryName) {
            return AZURE_MAPS_BASE_URL + "/search/poi/category/json" +
                    "?api-version=" + API_VERSION +
                    "&subscription-key=" + AZURE_MAPS_KEY +
                    "&query=" + categoryName + 
                    "&lat=" + latitude +
                    "&lon=" + longitude +
                    "&radius=" + SEARCH_RADIUS_METERS;
        }
    }

    // Speech Configuration (for Azure TTS)
    public static final class Speech {
        public static final String EN_VOICE_NAME = "en-US-JennyNeural";
        public static final String EN_LANG_CODE = "en-US";
        public static final String HI_VOICE_NAME = "hi-IN-SwaraNeural";
        public static final String HI_LANG_CODE = "hi-IN";
        public static final String KN_VOICE_NAME = "kn-IN-SapnaNeural";
        public static final String KN_LANG_CODE = "kn-IN";
        public static final String OUTPUT_FORMAT = "audio-16khz-128kbitrate-mono-mp3";
        public static final String DYNAMIC_SSML_TEMPLATE =
                "<speak version='1.0' xml:lang='%s'>" +
                "<voice name='%s'>%s</voice>" +
                "</speak>";
        public static String getTtsUrl() {
            return AZURE_SPEECH_ENDPOINT + "/cognitiveservices/v1";
        }
    }

    public static boolean isConfigValid() {
        return !AZURE_VISION_KEY.isEmpty() && !AZURE_VISION_KEY.equals("YOUR_AZURE_VISION_KEY_HERE") &&
               !AZURE_VISION_ENDPOINT.isEmpty() && !AZURE_VISION_ENDPOINT.equals("https://YOUR_RESOURCE_NAME.cognitiveservices.azure.com") &&
               !AZURE_MAPS_KEY.isEmpty() && !AZURE_MAPS_KEY.equals("YOUR_AZURE_MAPS_KEY_HERE") && 
               !AZURE_SPEECH_KEY.isEmpty() && !AZURE_SPEECH_KEY.equals("YOUR_AZURE_SPEECH_KEY_HERE");
    }

    public static String getConfigStatus() {
        if (isConfigValid()) {
            return "Azure services configured successfully";
        } else {
            StringBuilder issues = new StringBuilder("Please configure your Azure settings in AzureConfig.java:\n");
            if (AZURE_VISION_KEY.isEmpty() || AZURE_VISION_KEY.equals("YOUR_AZURE_VISION_KEY_HERE")) issues.append("- Azure Vision Key\n");
            if (AZURE_VISION_ENDPOINT.isEmpty() || AZURE_VISION_ENDPOINT.equals("https://YOUR_RESOURCE_NAME.cognitiveservices.azure.com")) issues.append("- Azure Vision Endpoint\n");
            if (AZURE_SPEECH_KEY.isEmpty() || AZURE_SPEECH_KEY.equals("YOUR_AZURE_SPEECH_KEY_HERE")) issues.append("- Azure Speech Key\n");
            if (AZURE_MAPS_KEY.isEmpty() || AZURE_MAPS_KEY.equals("YOUR_AZURE_MAPS_KEY_HERE")) issues.append("- Azure Maps Key\n");
            return issues.toString();
        }
    }
}
