package ml.puredark.hviewer.http;

import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.util.Pair;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ml.puredark.hviewer.HViewerApplication;
import ml.puredark.hviewer.helpers.Logger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class HViewerHttpClient {
    private static Handler mHandler = new Handler(Looper.getMainLooper());
    private static OkHttpClient mClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .dns(new HttpDns())
            .build();

    public static MediaType getContentType(String url, List<Pair<String, String>> headers) {
        Response response = getResponseHeader(url, headers);
        return response.body().contentType();
    }

    public static long getContentLength(String url, List<Pair<String, String>> headers) {
        Response response = getResponseHeader(url, headers);
        return (response != null) ? response.body().contentLength() : 0;
    }

    public static Response getResponseHeader(String url, List<Pair<String, String>> headers) {
        if (url == null || !url.startsWith("http")) {
            return null;
        }
        HRequestBuilder builder = new HRequestBuilder();
        if (headers != null) {
            for (Pair<String, String> header : headers) {
                builder.addHeader(header.first, header.second);
            }
        }
        Response response = null;
        try {
            Request request = builder
                    .url(url)
                    .head()
                    .build();
            response = mClient.newCall(request).execute();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (response != null) {
                response.close();
            }
        }
        return response;
    }

    public static Object get(String url, List<Pair<String, String>> headers) {
        if (url == null || !url.startsWith("http")) {
            Logger.d("HViewerHttpClient", "url = " + url);
            return null;
        }
        if (HViewerApplication.isNetworkAvailable()) {
            HRequestBuilder builder = new HRequestBuilder();
            if (headers != null) {
                for (Pair<String, String> header : headers) {
                    builder.addHeader(header.first, header.second);
                }
            }
            Request request = builder
                    .url(url)
                    .build();
            try {
                Response response = mClient.newCall(request).execute();
                String contentType = response.header("Content-Type");
                Object body;
                if (contentType != null && contentType.contains("image")) {
                    body = BitmapDrawable.createFromStream(response.body().byteStream(), null);
                } else {
                    byte[] b = response.body().bytes();
                    String charset = getCharset(new String(b));
                    body = new String(b, charset);
                }
                response.close();
                return body;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public static void get(String url, List<Pair<String, String>> headers, final OnResponseListener callback) {
        get(url, true, headers, false, null, callback);
    }

    public static void get(String url, boolean disableHProxy, List<Pair<String, String>> headers, boolean post, final OnResponseListener callback) {
        if (!post)
            get(url, disableHProxy, headers, false, null, callback);
        else {
            String params = (url == null) ? "" : (url.indexOf('?') > 0) ? url.substring(url.indexOf('?')) : url;
            post(url, disableHProxy, params, headers, callback);
        }
    }

    public static void get(String url, boolean disableHProxy, List<Pair<String, String>> headers, boolean post, RequestBody body, final OnResponseListener callback) {
        if (url == null || !url.startsWith("http")) {
            Logger.d("HViewerHttpClient", "url = " + url);
            callback.onFailure(new HttpError(HttpError.ERROR_WRONG_URL));
            return;
        }
        if (HViewerApplication.isNetworkAvailable()) {
            HRequestBuilder builder = new HRequestBuilder(disableHProxy);
            if (headers != null) {
                for (Pair<String, String> header : headers) {
                    builder.addHeader(header.first, header.second);
                }
            }
            if (post)
                builder.post(body);
            builder.url(url);
            Request request = builder.build();
            mClient.newCall(request).enqueue(new HCallback() {
                @Override
                void onFailure(IOException e) {
                    callback.onFailure(new HttpError(HttpError.ERROR_NETWORK));
                }

                @Override
                void onResponse(String contentType, Object body) {
                    callback.onSuccess(contentType, body);
                }
            });
        } else {
            callback.onFailure(new HttpError(HttpError.ERROR_NETWORK));
        }
    }

    public static void post(String url, boolean disableHProxy, String paramsString, List<Pair<String, String>> headers, final OnResponseListener callback) {
        String[] paramStrings = paramsString.split("&");
        FormBody.Builder formBody = new FormBody.Builder();
        for (String paramString : paramStrings) {
            String[] pram = paramString.split("=");
            if (pram.length != 2) continue;
            formBody.add(pram[0], pram[1]);
        }
        RequestBody requestBody = formBody.build();
        get(url, disableHProxy, headers, true, requestBody, callback);
    }

    /**
     * 获得字符集
     */
    public static String getCharset(String html) {
        Document doc = Jsoup.parse(html);
        Elements eles = doc.select("meta[http-equiv=Content-Type]");
        Iterator<Element> itor = eles.iterator();
        while (itor.hasNext())
            return matchCharset(itor.next().toString());
        return "utf-8";
    }

    /**
     * 获得页面字符
     */
    public static String matchCharset(String content) {
        String chs = "utf-8";
        Pattern p = Pattern.compile("(?<=charset=)(.+)(?=\")");
        Matcher m = p.matcher(content);
        if (m.find())
            return m.group();
        return chs;
    }

    public interface OnResponseListener {
        void onSuccess(String contentType, Object result);

        void onFailure(HttpError error);
    }

    // UI Thread Handler
    public static abstract class HCallback implements Callback {
        abstract void onFailure(IOException e);

        abstract void onResponse(String contentType, Object body);

        @Override
        public void onFailure(Call call, final IOException e) {
            e.printStackTrace();
            mHandler.post(() -> onFailure(e));
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            final String contentType = response.header("Content-Type");
            final Object body;
            if (contentType != null && contentType.contains("image")) {
                // 不经过图片加载库容易导致OOM，宁愿重新加载一次
//                body = BitmapFactory.decodeStream(response.body().byteStream());
                body = null;
            } else {
                byte[] b = response.body().bytes();
                String charset = getCharset(new String(b));
                body = new String(b, charset);
            }
            mHandler.post(() -> onResponse(contentType, body));
        }
    }

    // Pre-define error code
    public static class HttpError {
        // Error code constants
        public static final int ERROR_UNKNOWN = 1000;  //未知错误
        public static final int ERROR_JSON = 1001;  //JSON解析错误
        public static final int ERROR_NETWORK = 1009;  //网络错误
        public static final int ERROR_WRONG_URL = 1011;  //URL格式错误

        private int errorCode;
        private String errorString = "";

        public HttpError(int errorCode) {
            this.errorCode = errorCode;
            switch (errorCode) {
                case ERROR_UNKNOWN:
                    errorString = "未知错误";
                    break;
                case ERROR_JSON:
                    errorString = "JSON解析错误";
                    break;
                case ERROR_NETWORK:
                    errorString = "网络错误，请重试";
                    break;
                case ERROR_WRONG_URL:
                    errorString = "URL格式错误";
                    break;
                default:
                    errorString = "未定义的错误码";
                    break;
            }
        }

        public int getErrorCode() {
            return this.errorCode;
        }

        public String getErrorString() {
            return this.errorString;
        }

        @Override
        public String toString() {
            return errorCode + " : " + errorString;
        }
    }
}
