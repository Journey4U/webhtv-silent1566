package androidx.media3.mpvplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.ResponseBody;

public class MpvHlsProxyPlaylistLimitTest {

    private static final String MEDIA_TYPE = "application/vnd.apple.mpegurl";

    @Test
    public void exactPlaylistLimitIsAccepted() throws IOException {
        int limit = MpvHlsProxy.MAX_PLAYLIST_BYTES;
        byte[] bytes = new byte[limit];
        for (int i = 0; i < bytes.length; i++) bytes[i] = 'a';

        assertEquals(limit, MpvHlsProxy.readPlaylistBody(body(bytes)).length());
    }

    @Test
    public void playlistOneByteOverLimitIsRejected() {
        int limit = MpvHlsProxy.MAX_PLAYLIST_BYTES;
        byte[] bytes = new byte[limit + 1];
        for (int i = 0; i < bytes.length; i++) bytes[i] = 'a';

        assertTooLarge(body(bytes));
    }

    @Test
    public void oversizedDeclaredContentLengthIsRejectedWithoutReadingBody() {
        ResponseBody body = new FixedContentLengthBody(MpvHlsProxy.MAX_PLAYLIST_BYTES + 1);

        try {
            MpvHlsProxy.readPlaylistBody(body);
            fail("Expected oversized playlist rejection");
        } catch (IOException expected) {
            assertEquals("HLS playlist is too large", expected.getMessage());
        }
    }

    @Test
    public void malformedUtf8PlaylistIsRejected() {
        byte[] bytes = {'#', (byte) 0xff};
        try {
            MpvHlsProxy.readPlaylistBody(body(bytes));
            fail("Expected malformed UTF-8 rejection");
        } catch (IOException expected) {
            assertEquals("HLS playlist is not valid UTF-8", expected.getMessage());
        }
    }

    private static ResponseBody body(byte[] bytes) {
        return ResponseBody.create(MediaType.parse(MEDIA_TYPE), bytes);
    }

    private static void assertTooLarge(ResponseBody body) {
        try {
            MpvHlsProxy.readPlaylistBody(body);
            fail("Expected oversized playlist rejection");
        } catch (IOException expected) {
            assertEquals("HLS playlist is too large", expected.getMessage());
        }
    }

    private static final class FixedContentLengthBody extends ResponseBody {
        private final long contentLength;

        private FixedContentLengthBody(long contentLength) {
            this.contentLength = contentLength;
        }

        @Override public long contentLength() {
            return contentLength;
        }

        @Override public MediaType contentType() {
            return MediaType.parse(MEDIA_TYPE);
        }

        @Override public okio.BufferedSource source() {
            throw new AssertionError("body must not be read when Content-Length exceeds the limit");
        }
    }
}
