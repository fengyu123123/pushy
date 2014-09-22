/* Copyright (c) 2013 RelayRides
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.relayrides.pushy.apns;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.Future;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A connection to the APNs feedback service that listens for expired tokens, then disconnects after a period of
 * inactivity. According to Apple's documentation:</p>
 *
 * <blockquote><p>The Apple Push Notification Service includes a feedback service to give you information about failed
 * push notifications. When a push notification cannot be delivered because the intended app does not exist on the
 * device, the feedback service adds that device's token to its list. Push notifications that expire before being
 * delivered are not considered a failed delivery and don't impact the feedback service...</p>
 *
 * <p>Query the feedback service daily to get the list of device tokens. Use the timestamp to verify that the device
 * tokens haven't been reregistered since the feedback entry was generated. For each device that has not been
 * reregistered, stop sending notifications.</p></blockquote>
 *
 * <p>Generally, users of Pushy should <em>not</em> instantiate a {@code FeedbackServiceConnetion} directly, but should
 * instead call {@link com.relayrides.pushy.apns.PushManager#getExpiredTokens()}, which will manage the creation and
 * configuration of a {@code FeedbackServiceConnection} internally.</p>
 *
 * @author <a href="mailto:jon@relayrides.com">Jon Chambers</a>
 *
 * @see <a href="http://developer.apple.com/library/ios/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/Chapters/CommunicatingWIthAPS.html#//apple_ref/doc/uid/TP40008194-CH101-SW3">
 * Local and Push Notification Programming Guide - Provider Communication with Apple Push Notification Service - The
 * Feedback Service</a>
 */
class FeedbackServiceConnection {

	private final ApnsEnvironment environment;
	private final SSLContext sslContext;
	private final NioEventLoopGroup eventLoopGroup;
	private final FeedbackConnectionConfiguration configuration;

	private final List<ExpiredToken> expiredTokens = new ArrayList<ExpiredToken>();

	private static final Logger log = LoggerFactory.getLogger(FeedbackServiceConnection.class);

	private enum ExpiredTokenDecoderState {
		EXPIRATION,
		TOKEN_LENGTH,
		TOKEN
	}

	private class ExpiredTokenDecoder extends ReplayingDecoder<ExpiredTokenDecoderState> {

		private Date expiration;
		private byte[] token;

		public ExpiredTokenDecoder() {
			super(ExpiredTokenDecoderState.EXPIRATION);
		}

		@Override
		protected void decode(final ChannelHandlerContext context, final ByteBuf in, final List<Object> out) {
			switch (this.state()) {
				case EXPIRATION: {
					final long timestamp = (in.readInt() & 0xFFFFFFFFL) * 1000L;
					this.expiration = new Date(timestamp);

					this.checkpoint(ExpiredTokenDecoderState.TOKEN_LENGTH);

					break;
				}

				case TOKEN_LENGTH: {
					this.token = new byte[in.readShort() & 0x0000FFFF];
					this.checkpoint(ExpiredTokenDecoderState.TOKEN);

					break;
				}

				case TOKEN: {
					in.readBytes(this.token);
					out.add(new ExpiredToken(this.token, this.expiration));

					this.checkpoint(ExpiredTokenDecoderState.EXPIRATION);

					break;
				}
			}
		}
	}

	private class FeedbackClientHandler extends SimpleChannelInboundHandler<ExpiredToken> {

		private final FeedbackServiceConnection feedbackClient;

		public FeedbackClientHandler(final FeedbackServiceConnection feedbackClient) {
			this.feedbackClient = feedbackClient;
		}

		@Override
		protected void channelRead0(final ChannelHandlerContext context, final ExpiredToken expiredToken) {
			this.feedbackClient.expiredTokens.add(expiredToken);
		}

		@Override
		public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {

			if (!(cause instanceof ReadTimeoutException)) {
				log.debug("Caught an unexpected exception while waiting for expired tokens.", cause);
			}

			context.close();
		}
	}

	/**
	 * <p>Constructs a new feedback client that connects to the feedback service in the given environment with the
	 * credentials and key/trust managers in the given SSL context.</p>

	 * @param environment the environment in which this feedback client will operate
	 * @param sslContext an SSL context with the keys/certificates and trust managers this client should use when
	 * communicating with the APNs feedback service
	 * @param eventLoopGroup the event loop group this client should use for asynchronous network operations
	 * @param configuration the set of configuration options to use for this connection. The configuration object is
	 * copied and changes to the original object will not propagate to the connection after creation. Must not be
	 * {@code null}.
	 */
	public FeedbackServiceConnection(final ApnsEnvironment environment, final SSLContext sslContext, final NioEventLoopGroup eventLoopGroup, final FeedbackConnectionConfiguration configuration) {
		this.environment = environment;
		this.sslContext = sslContext;
		this.eventLoopGroup = eventLoopGroup;
		this.configuration = configuration;
	}

	/**
	 * <p>Retrieves a list of expired tokens from the APNs feedback service. Be warned that this is a
	 * <strong>destructive operation</strong>. According to Apple's documentation:</p>
	 *
	 * <blockquote>The feedback service's list is cleared after you read it. Each time you connect to the feedback
	 * service, the information it returns lists only the failures that have happened since you last
	 * connected.</blockquote>
	 *
	 * @return a list of tokens that have expired since the last connection to the feedback service
	 *
	 * @throws InterruptedException if interrupted while waiting for a response from the feedback service
	 * @throws FeedbackConnectionException if the connection to the feedback service failed for any reason
	 */
	public synchronized List<ExpiredToken> getExpiredTokens() throws InterruptedException, FeedbackConnectionException {

		this.expiredTokens.clear();

		final Bootstrap bootstrap = new Bootstrap();
		bootstrap.group(this.eventLoopGroup);
		bootstrap.channel(NioSocketChannel.class);

		final FeedbackServiceConnection feedbackConnection = this;
		bootstrap.handler(new ChannelInitializer<SocketChannel>() {

			@Override
			protected void initChannel(final SocketChannel channel) throws Exception {
				final ChannelPipeline pipeline = channel.pipeline();

				final SSLEngine sslEngine = feedbackConnection.sslContext.createSSLEngine();
				sslEngine.setUseClientMode(true);

				pipeline.addLast("ssl", new SslHandler(sslEngine));
				pipeline.addLast("readTimeoutHandler", new ReadTimeoutHandler(feedbackConnection.configuration.getReadTimeout()));
				pipeline.addLast("decoder", new ExpiredTokenDecoder());
				pipeline.addLast("handler", new FeedbackClientHandler(feedbackConnection));
			}

		});

		final ChannelFuture connectFuture = bootstrap.connect(
				this.environment.getFeedbackHost(),
				this.environment.getFeedbackPort()).await();

		if (connectFuture.isSuccess()) {
			log.debug("Connected to feedback service.");

			final SslHandler sslHandler = connectFuture.channel().pipeline().get(SslHandler.class);

			if (sslHandler != null) {
				final Future<Channel> handshakeFuture = sslHandler.handshakeFuture().await();

				if (handshakeFuture.isSuccess()) {
					log.debug("Completed TLS handshake with feedback service.");

					// The feedback service will send us a list of device tokens as soon as we complete the SSL
					// handshake, then hang up. While we're waiting to sync with the connection closure, we'll be
					// receiving messages from the feedback service from another thread.
					connectFuture.channel().closeFuture().await();
				} else {
					log.debug("Failed to complete TLS handshake with feedback service.", handshakeFuture.cause());

					connectFuture.channel().close().await();
					throw new FeedbackConnectionException(handshakeFuture.cause());
				}
			} else {
				log.warn("Feedback connection failed to get SSL handler and could not wait for TLS handshake.");

				connectFuture.channel().close().await();
				throw new FeedbackConnectionException(null);
			}
		} else {
			log.debug("Failed to connect to feedback service.", connectFuture.cause());
			throw new FeedbackConnectionException(connectFuture.cause());
		}

		return new ArrayList<ExpiredToken>(this.expiredTokens);
	}
}
