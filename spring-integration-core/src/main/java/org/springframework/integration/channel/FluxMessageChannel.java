/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.channel;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import org.springframework.messaging.Message;
import org.springframework.util.Assert;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * The {@link AbstractMessageChannel} implementation for the
 * Reactive Streams {@link Publisher} based on the Project Reactor {@link Flux}.
 *
 * @author Artem Bilan
 * @author Gary Russell
 * @author Sergei Egorov
 *
 * @since 5.0
 */
public class FluxMessageChannel extends AbstractMessageChannel
		implements Publisher<Message<?>>, ReactiveStreamsSubscribableChannel {

	private final Sinks.Many<Message<?>> sink;

	private final FluxProcessor<Message<?>, Message<?>> processor;

	private final Sinks.Many<Boolean> subscribedSignal = Sinks.many().replay().limit(1);

	private final Disposable.Composite upstreamSubscriptions = Disposables.composite();

	public FluxMessageChannel() {
		this.sink = Sinks.many().multicast().onBackpressureBuffer(1, false);
		this.processor = FluxProcessor.fromSink(this.sink);
	}

	@Override
	protected boolean doSend(Message<?> message, long timeout) {
		Assert.state(this.processor.hasDownstreams(),
				() -> "The [" + this + "] doesn't have subscribers to accept messages");
		return this.sink.tryEmitNext(message).hasSucceeded();
	}

	@Override
	public void subscribe(Subscriber<? super Message<?>> subscriber) {
		this.processor
				.doFinally((s) -> this.subscribedSignal.emitNext(this.processor.hasDownstreams()))
				.subscribe(subscriber);
		this.subscribedSignal.emitNext(this.processor.hasDownstreams());
	}

	@Override
	public void subscribeTo(Publisher<? extends Message<?>> publisher) {
		this.upstreamSubscriptions.add(
				Flux.from(publisher)
						.delaySubscription(this.subscribedSignal.asFlux().filter(Boolean::booleanValue).next())
						.publishOn(Schedulers.boundedElastic())
						.doOnNext((message) -> {
							try {
								send(message);
							}
							catch (Exception ex) {
								logger.warn("Error during processing event: " + message, ex);
							}
						})
						.subscribe());
	}

	@Override
	public void destroy() {
		this.subscribedSignal.emitNext(false);
		this.upstreamSubscriptions.dispose();
		this.processor.onComplete();
		super.destroy();
	}

}
