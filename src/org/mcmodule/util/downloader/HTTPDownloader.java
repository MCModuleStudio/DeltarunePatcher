package org.mcmodule.util.downloader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class HTTPDownloader extends BaseDownloader<HTTPDownloader.HTTPWorkerContext> {

	private static final int TIMEOUT = 10000; // 10秒超时
	private static final int MAX_RETRIES = 5;

	static {
		IEProxySupport.install();
	}

	public HTTPDownloader(File file, URL url, int numConnections, URL... backupURLs) throws IOException {
		super(file, url, numConnections, backupURLs);
	}

	@Override
	protected int getMaxRetries() {
		return MAX_RETRIES;
	}

	@Override
	protected HTTPWorkerContext createContext() {
		return new HTTPWorkerContext();
	}

	@Override
	protected FileInfo retrieveFileInfo(URL url) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("HEAD");
		conn.setConnectTimeout(TIMEOUT);
		conn.setReadTimeout(TIMEOUT);
		conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36");
		conn.setRequestProperty("Accept-Encoding", "identity");
		conn.setRequestProperty("Cache-Control", "no-cache");
		conn.connect();
		System.out.println(conn.getResponseCode());

		FileInfo info = new FileInfo();
		info.size = conn.getContentLengthLong();
		info.lastModified = conn.getLastModified();

		// 尝试从 Content-Disposition 获取文件名
		String disposition = conn.getHeaderField("Content-Disposition");
		if (disposition != null && disposition.contains("filename=")) {
			info.name = disposition.substring(disposition.indexOf("filename=") + 9).replace("\"", "");
		}

		conn.disconnect();
		return info;
	}

	@Override
	protected InputStream openConnection(HTTPWorkerContext ctx) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) ctx.url.openConnection();
		conn.setRequestMethod("GET");
		conn.setConnectTimeout(TIMEOUT);
		conn.setReadTimeout(TIMEOUT);

		// 设置 HTTP Range 请求，告诉服务器下载哪一部分
		// 格式: bytes=start-end
		String range = "bytes=" + ctx.from + "-" + (ctx.to - 1);
		conn.setRequestProperty("Range", range);
		conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36");
		conn.setRequestProperty("Accept-Encoding", "identity");
		conn.setRequestProperty("Cache-Control", "no-cache");

		conn.connect();

		// 检查响应码 (206 Partial Content 是支持断点续传的标志)
		int responseCode = conn.getResponseCode();
		if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
			conn.disconnect();
			throw new IOException("Server returned HTTP response code: " + responseCode);
		}

		// 将连接保存到上下文，以便后续关闭
		ctx.connection = conn;
		return conn.getInputStream();
	}

	@Override
	protected void closeConnection(HTTPWorkerContext ctx, InputStream is) {
		try {
			if (is != null)
				is.close();
		} catch (IOException ignored) {
		}

		if (ctx.connection != null) {
			ctx.connection.disconnect();
			ctx.connection = null;
		}
	}

	// 扩展上下文，用于持有 HTTP 连接实例
	static class HTTPWorkerContext extends BaseDownloader.WorkerContext {
		HttpURLConnection connection;
	}
}