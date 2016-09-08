package org.wordpress.android.fluxc.network.xmlrpc.media;

import android.support.annotation.NonNull;
import android.webkit.MimeTypeMap;

import com.android.volley.RequestQueue;
import com.android.volley.Response.Listener;
import com.android.volley.VolleyError;

import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.action.MediaAction;
import org.wordpress.android.fluxc.generated.MediaActionBuilder;
import org.wordpress.android.fluxc.model.MediaModel;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.network.BaseRequest;
import org.wordpress.android.fluxc.network.HTTPAuthManager;
import org.wordpress.android.fluxc.network.UserAgent;
import org.wordpress.android.fluxc.network.BaseUploadRequestBody.ProgressListener;
import org.wordpress.android.fluxc.network.rest.wpcom.auth.AccessToken;
import org.wordpress.android.fluxc.network.xmlrpc.BaseXMLRPCClient;
import org.wordpress.android.fluxc.network.xmlrpc.XMLRPCException;
import org.wordpress.android.fluxc.network.xmlrpc.XMLRPCFault;
import org.wordpress.android.fluxc.network.xmlrpc.XMLRPCRequest;
import org.wordpress.android.fluxc.network.xmlrpc.XMLSerializerUtils;
import org.wordpress.android.fluxc.store.MediaStore;
import org.wordpress.android.fluxc.store.MediaStore.MediaError;
import org.wordpress.android.fluxc.store.MediaStore.MediaErrorType;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.MapUtils;

import org.wordpress.android.fluxc.generated.endpoint.XMLRPC;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public class MediaXMLRPCClient extends BaseXMLRPCClient implements ProgressListener {
    // keys for de-serializing remote responses
    public static final String MEDIA_ID_KEY         = "attachment_id";
    public static final String POST_ID_KEY          = "parent";
    public static final String TITLE_KEY            = "title";
    public static final String CAPTION_KEY          = "caption";
    public static final String DESCRIPTION_KEY      = "description";
    public static final String VIDEOPRESS_GUID_KEY  = "videopress_shortcode";
    public static final String THUMBNAIL_URL_KEY    = "thumbnail";
    public static final String DATE_UPLOADED_KEY    = "date_created_gmt";
    public static final String LINK_KEY             = "link";
    public static final String METADATA_KEY         = "metadata";
    public static final String WIDTH_KEY            = "width";
    public static final String HEIGHT_KEY           = "height";

    // keys for pushing changes to existing remote media
    public static final String TITLE_EDIT_KEY       = "post_title";
    public static final String DESCRIPTION_EDIT_KEY = "post_content";
    public static final String CAPTION_EDIT_KEY     = "post_excerpt";

    private static final String FILE_NAME_REGEX = "^.*/([A-Za-z0-9_-]+)\\.\\w+$";

    private OkHttpClient mOkHttpClient;

    public MediaXMLRPCClient(Dispatcher dispatcher, RequestQueue requestQueue, OkHttpClient okClient,
                             AccessToken accessToken, UserAgent userAgent,
                             HTTPAuthManager httpAuthManager) {
        super(dispatcher, requestQueue, accessToken, userAgent, httpAuthManager);
        mOkHttpClient = okClient;
    }

    @Override
    public void onProgress(MediaModel media, float progress) {
        notifyMediaProgress(media, Math.max(0.99f, progress), null);
    }

    public void pushMedia(final SiteModel site, List<MediaModel> mediaList) {
        for (final MediaModel media : mediaList) {
            List<Object> params = getBasicParams(site);
            params.add(media.getMediaId());
            Map<String, Object> mediaFields = new HashMap<>();
            mediaFields.put(TITLE_EDIT_KEY, media.getTitle());
            mediaFields.put(DESCRIPTION_EDIT_KEY, media.getDescription());
            mediaFields.put(CAPTION_EDIT_KEY, media.getCaption());
            params.add(mediaFields);
            add(new XMLRPCRequest(site.getXmlRpcUrl(), XMLRPC.EDIT_POST, params, new Listener() {
                @Override
                public void onResponse(Object response) {
                    // response should be a boolean indicating result of push request
                    if (response == null || !(response instanceof Boolean) || !(Boolean) response) {
                        AppLog.w(T.MEDIA, "could not parse XMLRPC.EDIT_MEDIA response: " + response);
                        MediaError error = new MediaError(MediaErrorType.PARSE_ERROR);
                        notifyMediaPushed(MediaAction.PUSH_MEDIA, site, media, error);
                        return;
                    }

                    // success!
                    AppLog.i(T.MEDIA, "Media updated on remote: " + media.getTitle());
                    notifyMediaPushed(MediaAction.PUSH_MEDIA, site, media, null);
                }
            }, new BaseRequest.BaseErrorListener() {
                @Override
                public void onErrorResponse(@NonNull BaseRequest.BaseNetworkError error) {
                    AppLog.e(T.MEDIA, "error response to XMLRPC.EDIT_MEDIA request: " + error);
                    if (is404Response(error)) {
                        AppLog.e(T.MEDIA, "media does not exist, no need to report error");
                        notifyMediaPushed(MediaAction.PUSH_MEDIA, site, media, null);
                    } else {
                        MediaError mediaError = new MediaError(MediaErrorType.fromBaseNetworkError(error));
                        notifyMediaPushed(MediaAction.PUSH_MEDIA, site, media, mediaError);
                    }
                }
            }));
        }
    }

    public void uploadMedia(SiteModel site, MediaModel media) {
        performUpload(site, media);
    }

    public void fetchAllMedia(final SiteModel site) {
        List<Object> params = getBasicParams(site);
        add(new XMLRPCRequest(site.getXmlRpcUrl(), XMLRPC.GET_MEDIA_LIBRARY, params, new Listener() {
            @Override
            public void onResponse(Object response) {
                List<MediaModel> media = allMediaResponseToMediaModelList(response, site.getSiteId());
                if (media != null) {
                    AppLog.v(T.MEDIA, "Fetched all media for site via XMLRPC.GET_MEDIA_LIBRARY");
                    notifyMediaFetched(MediaAction.FETCH_ALL_MEDIA, site, media, null);
                } else {
                    AppLog.w(T.MEDIA, "could not parse XMLRPC.GET_MEDIA_LIBRARY response: " + response);
                    MediaError error = new MediaError(MediaErrorType.PARSE_ERROR);
                    notifyMediaFetched(MediaAction.FETCH_ALL_MEDIA, site, (MediaModel) null, error);
                }
            }
        }, new BaseRequest.BaseErrorListener() {
            @Override
            public void onErrorResponse(@NonNull BaseRequest.BaseNetworkError error) {
                AppLog.e(T.MEDIA, "XMLRPC.GET_MEDIA_LIBRARY error response:", error.volleyError);
                MediaError mediaError = new MediaError(MediaErrorType.fromBaseNetworkError(error));
                notifyMediaFetched(MediaAction.FETCH_ALL_MEDIA, site, (MediaModel) null, mediaError);
            }
        }));
    }

    public void fetchMedia(final SiteModel site, List<MediaModel> mediaToFetch) {
        if (site == null || mediaToFetch == null || mediaToFetch.isEmpty()) return;

        for (final MediaModel media : mediaToFetch) {
            List<Object> params = getBasicParams(site);
            params.add(media.getMediaId());
            add(new XMLRPCRequest(site.getXmlRpcUrl(), XMLRPC.GET_MEDIA_ITEM, params, new Listener() {
                @Override
                public void onResponse(Object response) {
                    AppLog.v(T.MEDIA, "Fetched media for site via XMLRPC.GET_MEDIA_ITEM");
                    MediaModel responseMedia = responseMapToMediaModel((HashMap) response, site.getSiteId());
                    if (responseMedia != null) {
                        AppLog.v(T.MEDIA, "Fetched media with ID: " + media.getMediaId());
                        notifyMediaFetched(MediaAction.FETCH_MEDIA, site, responseMedia, null);
                    } else {
                        AppLog.w(T.MEDIA, "could not parse Fetch media response, ID: " + media.getMediaId());
                        MediaError error = new MediaError(MediaErrorType.PARSE_ERROR);
                        notifyMediaFetched(MediaAction.FETCH_MEDIA, site, media, error);
                    }
                }
            }, new BaseRequest.BaseErrorListener() {
                @Override
                public void onErrorResponse(@NonNull BaseRequest.BaseNetworkError error) {
                    AppLog.v(T.MEDIA, "XMLRPC.GET_MEDIA_ITEM error response: " + error);
                    MediaError mediaError = new MediaError(MediaErrorType.fromBaseNetworkError(error));
                    notifyMediaFetched(MediaAction.FETCH_MEDIA, site, media, mediaError);
                }
            }));
        }
    }

    public void deleteMedia(final SiteModel site, final List<MediaModel> mediaToDelete) {
        if (site == null || mediaToDelete == null || mediaToDelete.isEmpty()) return;

        for (final MediaModel media : mediaToDelete) {
            List<Object> params = getBasicParams(site);
            params.add(media.getMediaId());
            add(new XMLRPCRequest(site.getXmlRpcUrl(), XMLRPC.DELETE_POST, params, new Listener() {
                @Override
                public void onResponse(Object response) {
                    // response should be a boolean indicating result of push request
                    if (response == null || !(response instanceof Boolean) || !(Boolean) response) {
                        AppLog.w(T.MEDIA, "could not parse XMLRPC.DELETE_MEDIA response: " + response);
                        MediaError error = new MediaError(MediaErrorType.PARSE_ERROR);
                        notifyMediaDeleted(MediaAction.FETCH_ALL_MEDIA, site, media, error);
                        return;
                    }

                    AppLog.v(T.MEDIA, "Successful response from XMLRPC.DELETE_MEDIA");
                }
            }, new BaseRequest.BaseErrorListener() {
                @Override
                public void onErrorResponse(@NonNull BaseRequest.BaseNetworkError error) {
                    AppLog.v(T.MEDIA, "Error response from XMLRPC.DELETE_MEDIA:" + error);
                    MediaErrorType mediaError = MediaErrorType.fromBaseNetworkError(error);
                    if (mediaError == MediaErrorType.MEDIA_NOT_FOUND) {
                        AppLog.i(T.MEDIA, "Attempted to delete media that does not exist remotely.");
                        notifyMediaDeleted(MediaAction.DELETE_MEDIA, site, media, null);
                    } else {
                        notifyMediaDeleted(MediaAction.FETCH_MEDIA, site, media, new MediaError(mediaError));
                    }
                }
            }));
        }
    }

    private void performUpload(SiteModel site, final MediaModel media) {
        URL xmlrpcUrl;
        try {
            xmlrpcUrl = new URL(site.getXmlRpcUrl());
        } catch (MalformedURLException e) {
            AppLog.w(T.MEDIA, "bad XMLRPC URL for site: " + site.getXmlRpcUrl());
            return;
        }

        XmlrpcUploadRequestBody requestBody = new XmlrpcUploadRequestBody(media, this, site);
        HttpUrl url = new HttpUrl.Builder()
                .scheme(xmlrpcUrl.getProtocol())
                .host(xmlrpcUrl.getHost())
                .encodedPath(xmlrpcUrl.getPath())
                .username(site.getUsername())
                .password(site.getPassword())
                .build();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        mOkHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, okhttp3.Response response) throws IOException {
                if (response.code() == HttpURLConnection.HTTP_OK) {
                    AppLog.d(T.MEDIA, "media upload successful: " + media.getTitle());
                    MediaModel responseMedia = responseXmlToMediaModel(response);
                    notifyMediaUploaded(responseMedia, null);
                } else {
                    AppLog.w(T.MEDIA, "error uploading media: " + response);
                    MediaStore.MediaError error = new MediaError(MediaErrorType.fromHttpStatusCode(response.code()));
                    notifyMediaUploaded(media, error);
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                AppLog.w(T.MEDIA, "media upload failed: " + e);
                MediaStore.MediaError error = new MediaError(MediaErrorType.GENERIC_ERROR);
                notifyMediaUploaded(media, error);
            }
        });
    }

    private List<Object> getBasicParams(SiteModel site) {
        List<Object> params = new ArrayList<>();
        params.add(site.getSelfHostedSiteId());
        params.add(site.getUsername());
        params.add(site.getPassword());
        return params;
    }

    private List<MediaModel> allMediaResponseToMediaModelList(Object response, long siteId) {
        if (!(response instanceof Object[])) return null;

        Object[] responseArray = (Object[]) response;
        List<MediaModel> responseMedia = new ArrayList<>();
        for (Object mediaObject : responseArray) {
            if (!(mediaObject instanceof HashMap)) continue;
            MediaModel media = responseMapToMediaModel((HashMap) mediaObject, siteId);
            if (media != null) responseMedia.add(media);
        }

        return responseMedia;
    }

    private MediaModel responseXmlToMediaModel(okhttp3.Response response) {
        MediaModel media = new MediaModel();
        try {
            String data = new String(response.body().bytes(), "UTF-8");
            InputStream is = new ByteArrayInputStream(data.getBytes(Charset.forName("UTF-8")));
            Object obj = XMLSerializerUtils.deserialize(XMLSerializerUtils.scrubXmlResponse(is));
            if (obj instanceof Map) {
                Map<String, String> map = (Map) obj;
                media.setMediaId(Long.parseLong(map.get(MEDIA_ID_KEY)));
            }
        } catch (IOException | XMLRPCException | XmlPullParserException e) {
        }
        return media;
    }

    private MediaModel responseMapToMediaModel(HashMap<String, ?> responseMap, long siteId) {
        if (responseMap == null || responseMap.isEmpty()) return null;

        String link = MapUtils.getMapStr(responseMap, LINK_KEY);
        String fileExtension = MimeTypeMap.getFileExtensionFromUrl(link);

        MediaModel mediaModel = new MediaModel();
        mediaModel.setSiteId(siteId);
        mediaModel.setMediaId(MapUtils.getMapLong(responseMap, MEDIA_ID_KEY));
        mediaModel.setPostId(MapUtils.getMapLong(responseMap, POST_ID_KEY));
        mediaModel.setTitle(MapUtils.getMapStr(responseMap, TITLE_KEY));
        mediaModel.setCaption(MapUtils.getMapStr(responseMap, CAPTION_KEY));
        mediaModel.setDescription(MapUtils.getMapStr(responseMap, DESCRIPTION_KEY));
        mediaModel.setVideoPressGuid(MapUtils.getMapStr(responseMap, VIDEOPRESS_GUID_KEY));
        mediaModel.setUrl(link);
        mediaModel.setFileName(link.replaceAll(FILE_NAME_REGEX, "$1"));
        mediaModel.setFileExtension(fileExtension);
        mediaModel.setMimeType(MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension));
        mediaModel.setThumbnailUrl(MapUtils.getMapStr(responseMap, THUMBNAIL_URL_KEY));
        mediaModel.setUploadDate(MapUtils.getMapDate(responseMap, DATE_UPLOADED_KEY).toString());

        Object metadataObject = responseMap.get(METADATA_KEY);
        if (metadataObject instanceof Map) {
            Map metadataMap = (Map) metadataObject;
            mediaModel.setWidth(MapUtils.getMapInt(metadataMap, WIDTH_KEY));
            mediaModel.setHeight(MapUtils.getMapInt(metadataMap, HEIGHT_KEY));
        }

        return mediaModel;
    }

    private boolean is404Response(BaseRequest.BaseNetworkError error) {
        if (error.hasVolleyError() && error.volleyError != null) {
            VolleyError volleyError = error.volleyError;
            if (volleyError.networkResponse != null
            && volleyError.networkResponse.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return true;
            }

            if (volleyError.getCause() instanceof XMLRPCFault) {
                if (((XMLRPCFault) volleyError.getCause()).getFaultCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                    return true;
                }
            }
        }

        if (error.isGeneric() && error.type == BaseRequest.GenericErrorType.NOT_FOUND) {
            return true;
        }

        return false;
    }

    private void notifyMediaProgress(MediaModel media, float progress, MediaError error) {
        AppLog.v(AppLog.T.MEDIA, "Progress update on upload of " + media.getTitle() + ": " + progress);
        MediaStore.ProgressPayload payload = new MediaStore.ProgressPayload(media, progress, false);
        payload.error = error;
        mDispatcher.dispatch(MediaActionBuilder.newUploadedMediaAction(payload));
    }

    private void notifyMediaFetched(MediaAction cause, SiteModel site, MediaModel media, MediaError error) {
        List<MediaModel> mediaList = new ArrayList<>();
        mediaList.add(media);
        notifyMediaFetched(cause, site, mediaList, error);
    }

    private void notifyMediaFetched(MediaAction cause, SiteModel site, List<MediaModel> mediaList, MediaError error) {
        MediaStore.MediaListPayload payload = new MediaStore.MediaListPayload(cause, site, mediaList);
        payload.error = error;
        mDispatcher.dispatch(MediaActionBuilder.newFetchedMediaAction(payload));
    }

    private void notifyMediaPushed(MediaAction cause, SiteModel site, MediaModel media, MediaError error) {
        List<MediaModel> mediaList = new ArrayList<>();
        mediaList.add(media);
        notifyMediaPushed(cause, site, mediaList, error);
    }

    private void notifyMediaPushed(MediaAction cause, SiteModel site, List<MediaModel> mediaList, MediaError error) {
        MediaStore.MediaListPayload payload = new MediaStore.MediaListPayload(cause, site, mediaList);
        payload.error = error;
        mDispatcher.dispatch(MediaActionBuilder.newPushedMediaAction(payload));
    }

    private void notifyMediaUploaded(MediaModel media, MediaError error) {
        MediaStore.ProgressPayload payload = new MediaStore.ProgressPayload(media, 1.f, error == null);
        payload.error = error;
        mDispatcher.dispatch(MediaActionBuilder.newUploadedMediaAction(payload));
    }

    private void notifyMediaDeleted(MediaAction cause, SiteModel site, MediaModel media, MediaError error) {
        List<MediaModel> mediaList = new ArrayList<>();
        mediaList.add(media);
        MediaStore.MediaListPayload payload = new MediaStore.MediaListPayload(cause, site, mediaList);
        payload.error = error;
        mDispatcher.dispatch(MediaActionBuilder.newDeletedMediaAction(payload));
    }
}
