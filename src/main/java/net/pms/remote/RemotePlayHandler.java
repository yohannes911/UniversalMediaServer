package net.pms.remote;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.List;
import net.pms.PMS;
import net.pms.configuration.FormatConfiguration;
import net.pms.configuration.PmsConfiguration;
import net.pms.configuration.WebRender;
import net.pms.dlna.DLNAMediaInfo;
import net.pms.dlna.DLNAResource;
import net.pms.dlna.RootFolder;
import net.pms.dlna.virtual.VirtualVideoAction;
import net.pms.encoders.FFMpegVideo;
import net.pms.encoders.Player;
import net.pms.formats.v2.SubtitleType;
import net.pms.io.OutputParams;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemotePlayHandler implements HttpHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(RemotePlayHandler.class);
	private final static String CRLF = "\r\n";
	private RemoteWeb parent;
	private static final PmsConfiguration configuration = PMS.getConfiguration();

	public RemotePlayHandler(RemoteWeb parent) {
		this.parent = parent;
	}

	private void endPage(StringBuilder sb) {
		sb.append("</div>").append(CRLF);
		sb.append("</div>").append(CRLF);
		sb.append("</body>").append(CRLF);
		sb.append("</html>").append(CRLF);
	}

	private DLNAResource findNext(int start, int inc, List<DLNAResource> list) {
		int nxtPos = start;
		while ((nxtPos < list.size()) && (nxtPos >= 0)) {
			// if we're not last/first in list just pick next/prev from child list
			DLNAResource n = list.get(nxtPos);
			if (!n.isFolder()) {
				return n;
			}
			nxtPos += inc;
		}
		return null;
	}

	private String mkPage(String id, HttpExchange t) throws IOException {
		boolean flowplayer = true;

		LOGGER.debug("make play page " + id);
		RootFolder root = parent.getRoot(RemoteUtil.userName(t), t);
		if (root == null) {
			LOGGER.debug("root not found");
			throw new IOException("Unknown root");
		}
		WebRender renderer = (WebRender)root.getDefaultRenderer();
		renderer.setBrowserInfo(RemoteUtil.getCookie("UMSINFO", t), t.getRequestHeaders().getFirst("User-agent"));
		//List<DLNAResource> res = root.getDLNAResources(id, false, 0, 0, renderer);
		DLNAResource r = root.getDLNAResource(id, renderer);
		if (r == null) {
			LOGGER.debug("Bad id in web if "+id);
			throw new IOException("Bad Id");
		}
		String auto = " autoplay>";
		String query = t.getRequestURI().getQuery();
		boolean forceFlash = StringUtils.isNotEmpty(RemoteUtil.getQueryVars(query, "flash"));
		// next/prev handling
		String dir = RemoteUtil.getQueryVars(query, "nxt");
		if(StringUtils.isNotEmpty(dir)) {
			// if the "nxt" field is set we should calculate the next media
			// 1st fetch or own index in the child list
			List<DLNAResource> children = r.getParent().getChildren();
			int i = children.indexOf(r);
			DLNAResource n = null;
			int inc;
			int loopPos;
			if (dir.equals("next")) {
				inc = 1;
				loopPos = 0;
			} else {
				inc = -1;
				loopPos = children.size() - 1;
			}
			n = findNext(i + inc, inc, children);
			if (n == null && configuration.getWebAutoLoop(r.getFormat())) {
				// we were last/first so if we loop pick first/last in list
				n = findNext(loopPos, inc, children);
			}
			if (n != null) {
				// all done, change the id
				id = n.getResourceId();
				r = n;
			} else {
				// trick here to stop continuing if loop is off
				auto = ">";
			}
		}
		String id1 = URLEncoder.encode(id, "UTF-8");
		String rawId = id;

		String nxtJs = "window.location.replace('/play/" + id1 + "?nxt=next');";
		String prvJs = "window.location.replace('/play/" + id1 + "?nxt=prev');";
		// hack here to ensure we got a root folder to use for recently played etc.
		root.getDefaultRenderer().setRootFolder(root);
		String mime = root.getDefaultRenderer().getMimeType(r.mimeType());
		String mediaType = "";
		String coverImage = "";
		if (r instanceof VirtualVideoAction) {
			// for VVA we just call the enable fun directly
			// waste of resource to play dummy video
			((VirtualVideoAction) r).enable();
			// special page to return
			return "<html><head><script>window.refresh=true;history.back()</script></head></html>";
		}
		if (r.getFormat().isImage()) {
			flowplayer = false;
			coverImage = "<img src=\"/raw/" + rawId + "\" alt=\"\"><br>";
		}
		if (r.getFormat().isAudio()) {
			mediaType = "audio";
			String thumb = "/thumb/" + id1;
			String name = StringEscapeUtils.escapeHtml(r.resumeName());
			coverImage = "<img height=256 width=256 src=\"" + thumb + "\" alt=\"\"><br><h2>" + name + "</h2><br>";
			flowplayer = false;
		}
		if (r.getFormat().isVideo()) {
			mediaType = "video";
			if (mime.equals(FormatConfiguration.MIMETYPE_AUTO)) {
				if (r.getMedia() != null && r.getMedia().getMimeType() != null) {
					mime = r.getMedia().getMimeType();
				}
			}
			if (!configuration.getWebFlash() && !forceFlash)  {
				if(!RemoteUtil.directmime(mime) || RemoteUtil.transMp4(mime, r.getMedia()) || r.isResume()) {
					WebRender render = (WebRender)r.getDefaultRenderer();
					mime = render != null ? render.getVideoMimeType() : RemoteUtil.transMime();
					flowplayer = false;
				}
			}
		}

		// Media player HTML
		StringBuilder sb = new StringBuilder();
		sb.append("<!DOCTYPE html>").append(CRLF);
			sb.append("<head>").append(CRLF);
				sb.append("<link rel=\"stylesheet\" href=\"/files/reset.css\" type=\"text/css\" media=\"screen\">").append(CRLF);
				sb.append("<link rel=\"stylesheet\" href=\"/files/web.css\" type=\"text/css\" media=\"screen\">").append(CRLF);
				sb.append("<link rel=\"stylesheet\" href=\"/files/web-narrow.css\" type=\"text/css\" media=\"screen and (max-width: 1080px)\">").append(CRLF);
				sb.append("<link rel=\"stylesheet\" href=\"/files/web-wide.css\" type=\"text/css\" media=\"screen and (min-width: 1081px)\">").append(CRLF);
				sb.append("<link rel=\"icon\" href=\"/files/favicon.ico\" type=\"image/x-icon\">").append(CRLF);
				sb.append("<title>Universal Media Server</title>").append(CRLF);
				if (flowplayer) {
					sb.append("<script src=\"/files/jquery.min.js\"></script>").append(CRLF);
					sb.append("<script src=\"/files/flowplayer.min.js\"></script>").append(CRLF);
					sb.append("<link rel=\"stylesheet\" href=\"/files/functional.css\">").append(CRLF);
				}
				sb.append(WebRender.umsInfoScript).append(CRLF);
			sb.append("</head>").append(CRLF);
			sb.append("<body id=\"ContentPage\">").append(CRLF);
				sb.append("<div id=\"Container\">").append(CRLF);
					sb.append("<div id=\"Menu\">").append(CRLF);
						sb.append("<a href=\"/browse/0\" id=\"HomeButton\"></a>").append(CRLF);
					sb.append("</div>").append(CRLF);
					sb.append("<div id=\"VideoContainer\">").append(CRLF);
					// for video this gives just an empty line
					sb.append(coverImage).append(CRLF);
					if (r.getFormat().isImage()) {
						// do this like this to simplify the code
						// skip all player crap since img tag works well
						int delay = configuration.getWebImgSlideDelay() * 1000;
						if (delay > 0 && configuration.getWebAutoCont(r.getFormat())) {
							sb.append("<script>").append(CRLF);
							sb.append("setTimeout(\"").append(nxtJs).append("\",").append(delay).append(");").append(CRLF);
							sb.append("</script>").append(CRLF);
						}
						endPage(sb);
						return sb.toString();
					}

					if (flowplayer) {
						//sb.append("<div class=\"flowplayer no-time no-volume no-mute\" data-ratio=\"0.5625\" data-embed=\"false\" data-flashfit=\"true\">").append(CRLF);
						sb.append("<div class=\"player\">").append(CRLF);
					}
					sb.append("<").append(mediaType);
					if (flowplayer) {
						sb.append(" controls").append(auto).append(CRLF);
						if (
							RemoteUtil.directmime(mime) &&
							!RemoteUtil.transMp4(mime, r.getMedia()) &&
							!r.isResume() &&
							!forceFlash
						) {
							sb.append("<source src=\"/media/").append(id1).append("\" type=\"").append(mime).append("\">").append(CRLF);
						}
						sb.append("<source src=\"/fmedia/").append(id1).append("\" type=\"video/flash\">");
					} else {
						sb.append(" id=\"player\" width=\"").append(RemoteUtil.getWidth()).append("\" height=\"");
						sb.append(RemoteUtil.getHeight()).append("\" controls").append(auto).append(CRLF);
						sb.append("<source src=\"/media/").append(id1).append("\" type=\"").append(mime).append("\">");
					}
					sb.append(CRLF);

					if (configuration.getWebSubs()) {
						// only if subs are requested as <track> tags
						// otherwise we'll transcode them in
						boolean isFFmpegFontConfig = configuration.isFFmpegFontConfig();
						if (isFFmpegFontConfig) { // do not apply fontconfig to flowplayer subs
							configuration.setFFmpegFontConfig(false);
						}
						OutputParams p = new OutputParams(configuration);
						p.sid = r.getMediaSubtitle();
						Player.setAudioAndSubs(r.getName(), r.getMedia(), p);
						if (p.sid !=null && p.sid.getType().isText()) {
							try {
								File subFile = FFMpegVideo.getSubtitles(r, r.getMedia(), p, configuration, SubtitleType.WEBVTT);
								LOGGER.debug("subFile " + subFile);
								if (subFile != null) {
									sb.append("<track kind=\"subtitles\" src=\"/subs/").append(subFile.getAbsolutePath()).append("\" default>");
								}
							} catch (Exception e) {
								LOGGER.debug("error when doing sub file " + e);
							}
						}
						
						configuration.setFFmpegFontConfig(isFFmpegFontConfig); // return back original fontconfig value
					}
					sb.append("</").append(mediaType).append(">").append(CRLF);

					if (flowplayer) {
						sb.append("</div>").append(CRLF);
					}
		// nex and prev buttons
		sb.append("<div>").append(CRLF);
		sb.append("<button value=\"<<\" onclick=\"").append(prvJs).append("\"><<</button>").append(CRLF);
		sb.append("<button value=\">>\" onclick=\"").append(nxtJs).append("\">>></button>").append(CRLF);
		if(!forceFlash && !flowplayer && r.getFormat().isVideo()) {
			// only add flash button for videos (and we aren't playing flash already)
			String flashStr = "window.location.replace('/play/" + id1 + "?flash=1');";
			sb.append("<button value=\"flash\" onclick=\"").append(flashStr).append("\">Flash</button>").append(CRLF);
		}
		sb.append("</div>").append(CRLF);
		sb.append("</div>").append(CRLF);
		sb.append("<a href=\"/raw/").append(rawId).append("\" target=\"_blank\" id=\"DownloadLink\" title=\"Download this video\"></a>").append(CRLF);
		if (flowplayer) {
			sb.append("<script>").append(CRLF);
			sb.append("$(function() {").append(CRLF);
			if (configuration.getWebAutoCont(r.getFormat())) {
				// auto continue for flowplayer
				sb.append("var api = $(\".player\").flowplayer();").append(CRLF);
				sb.append("               api.bind(\"finish\",function() {").append(CRLF);
				sb.append(nxtJs).append(CRLF);
				sb.append("               });").append(CRLF);
			}
			sb.append("	$(\".player\").flowplayer({").append(CRLF);
			sb.append("		ratio: 9/16,").append(CRLF);
			sb.append("		flashfit: true").append(CRLF);
			sb.append("	});").append(CRLF);
			sb.append("});").append(CRLF);
			sb.append("</script>").append(CRLF);
		} else {
			if (configuration.getWebAutoCont(r.getFormat())) {
				// logic here use our own id (for example 123)
				// once we're done ask for /play/123?nxt=true
				// the nxt will cause us to pick next (most likely 124) from list.
				sb.append("<script>").append(CRLF);
				sb.append("var player = document.getElementById(\"player\");").append(CRLF);
				sb.append("player.addEventListener(\"ended\", function() {").append(CRLF);
				sb.append(nxtJs).append(CRLF);
				sb.append("});").append(CRLF);
				sb.append("</script>").append(CRLF);
			}
		}
		endPage(sb);

		return sb.toString();
	}

	private boolean transMp4(String mime, DLNAMediaInfo media) {
		LOGGER.debug("mp4 profile " + media.getH264Profile());
		return mime.equals("video/mp4") && (configuration.isWebMp4Trans() || media.getAvcAsInt() >= 40);
	}

	@Override
	public void handle(HttpExchange t) throws IOException {
		LOGGER.debug("got a play request " + t.getRequestURI());
		if (RemoteUtil.deny(t)) {
			throw new IOException("Access denied");
		}
		String id;
		id = RemoteUtil.getId("play/", t);
		String response = mkPage(id, t);
		Headers hdr = t.getResponseHeaders();
		hdr.add("Content-Type", "text/html");
		LOGGER.debug("play page " + response);
		t.sendResponseHeaders(200, response.length());
		try (OutputStream os = t.getResponseBody()) {
			os.write(response.getBytes());
		}
	}
}
