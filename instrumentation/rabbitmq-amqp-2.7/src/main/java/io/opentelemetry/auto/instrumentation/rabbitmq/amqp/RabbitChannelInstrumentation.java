/*
 * Copyright 2020, OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.opentelemetry.auto.instrumentation.rabbitmq.amqp;

import static io.opentelemetry.auto.instrumentation.rabbitmq.amqp.RabbitCommandInstrumentation.SpanHolder.CURRENT_RABBIT_SPAN;
import static io.opentelemetry.auto.instrumentation.rabbitmq.amqp.RabbitDecorator.CONSUMER_DECORATE;
import static io.opentelemetry.auto.instrumentation.rabbitmq.amqp.RabbitDecorator.DECORATE;
import static io.opentelemetry.auto.instrumentation.rabbitmq.amqp.RabbitDecorator.PRODUCER_DECORATE;
import static io.opentelemetry.auto.instrumentation.rabbitmq.amqp.RabbitDecorator.TRACER;
import static io.opentelemetry.auto.instrumentation.rabbitmq.amqp.TextMapExtractAdapter.GETTER;
import static io.opentelemetry.auto.instrumentation.rabbitmq.amqp.TextMapInjectAdapter.SETTER;
import static io.opentelemetry.auto.tooling.bytebuddy.matcher.AgentElementMatchers.hasInterface;
import static io.opentelemetry.trace.Span.Kind.CLIENT;
import static io.opentelemetry.trace.Span.Kind.PRODUCER;
import static net.bytebuddy.matcher.ElementMatchers.canThrow;
import static net.bytebuddy.matcher.ElementMatchers.isGetter;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isSetter;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.MessageProperties;
import io.opentelemetry.auto.bootstrap.CallDepthThreadLocalMap;
import io.opentelemetry.auto.instrumentation.api.MoreTags;
import io.opentelemetry.auto.instrumentation.api.SpanWithScope;
import io.opentelemetry.auto.instrumentation.api.Tags;
import io.opentelemetry.auto.tooling.Instrumenter;
import io.opentelemetry.context.Scope;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.SpanContext;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class RabbitChannelInstrumentation extends Instrumenter.Default {

  public RabbitChannelInstrumentation() {
    super("amqp", "rabbitmq");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return not(isInterface()).and(hasInterface(named("com.rabbitmq.client.Channel")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "io.opentelemetry.auto.decorator.BaseDecorator",
      "io.opentelemetry.auto.decorator.ClientDecorator",
      packageName + ".RabbitDecorator",
      packageName + ".RabbitDecorator$1",
      packageName + ".RabbitDecorator$2",
      packageName + ".TextMapInjectAdapter",
      packageName + ".TextMapExtractAdapter",
      packageName + ".TracedDelegatingConsumer",
      RabbitCommandInstrumentation.class.getName() + "$SpanHolder",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    // We want the advice applied in a specific order, so use an ordered map.
    final Map<ElementMatcher<? super MethodDescription>, String> transformers =
        new LinkedHashMap<>();
    transformers.put(
        isMethod()
            .and(
                not(
                    isGetter()
                        .or(isSetter())
                        .or(nameEndsWith("Listener"))
                        .or(nameEndsWith("Listeners"))
                        .or(named("processAsync"))
                        .or(named("open"))
                        .or(named("close"))
                        .or(named("abort"))
                        .or(named("basicGet"))))
            .and(isPublic())
            .and(canThrow(IOException.class).or(canThrow(InterruptedException.class))),
        RabbitChannelInstrumentation.class.getName() + "$ChannelMethodAdvice");
    transformers.put(
        isMethod().and(named("basicPublish")).and(takesArguments(6)),
        RabbitChannelInstrumentation.class.getName() + "$ChannelPublishAdvice");
    transformers.put(
        isMethod().and(named("basicGet")).and(takesArgument(0, String.class)),
        RabbitChannelInstrumentation.class.getName() + "$ChannelGetAdvice");
    transformers.put(
        isMethod()
            .and(named("basicConsume"))
            .and(takesArgument(0, String.class))
            .and(takesArgument(6, named("com.rabbitmq.client.Consumer"))),
        RabbitChannelInstrumentation.class.getName() + "$ChannelConsumeAdvice");
    return transformers;
  }

  public static class ChannelMethodAdvice {
    @Advice.OnMethodEnter
    public static SpanWithScope onEnter(
        @Advice.This final Channel channel, @Advice.Origin("Channel.#m") final String method) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Channel.class);
      if (callDepth > 0) {
        return null;
      }

      final Connection connection = channel.getConnection();

      final Span.Builder spanBuilder = TRACER.spanBuilder("amqp.command");
      if (method.equals("Channel.basicPublish")) {
        spanBuilder.setSpanKind(PRODUCER);
      } else {
        spanBuilder.setSpanKind(CLIENT);
      }
      final Span span = spanBuilder.startSpan();
      span.setAttribute(MoreTags.RESOURCE_NAME, method);
      span.setAttribute(Tags.PEER_PORT, connection.getPort());
      DECORATE.afterStart(span);
      DECORATE.onPeerConnection(span, connection.getAddress());
      CURRENT_RABBIT_SPAN.set(span);
      return new SpanWithScope(span, TRACER.withSpan(span));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final SpanWithScope spanWithScope, @Advice.Thrown final Throwable throwable) {
      if (spanWithScope == null) {
        return;
      }
      CallDepthThreadLocalMap.reset(Channel.class);

      CURRENT_RABBIT_SPAN.remove();
      final Span span = spanWithScope.getSpan();
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      span.end();
      spanWithScope.closeScope();
    }
  }

  public static class ChannelPublishAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void setResourceNameAddHeaders(
        @Advice.Argument(0) final String exchange,
        @Advice.Argument(1) final String routingKey,
        @Advice.Argument(value = 4, readOnly = false) AMQP.BasicProperties props,
        @Advice.Argument(5) final byte[] body) {
      final Span span = TRACER.getCurrentSpan();

      if (span.getContext().isValid()) {
        PRODUCER_DECORATE.afterStart(span); // Overwrite tags set by generic decorator.
        PRODUCER_DECORATE.onPublish(span, exchange, routingKey);
        span.setAttribute("message.size", body == null ? 0 : body.length);

        // This is the internal behavior when props are null.  We're just doing it earlier now.
        if (props == null) {
          props = MessageProperties.MINIMAL_BASIC;
        }
        final Integer deliveryMode = props.getDeliveryMode();
        if (deliveryMode != null) {
          span.setAttribute("amqp.delivery_mode", deliveryMode);
        }

        // We need to copy the BasicProperties and provide a header map we can modify
        Map<String, Object> headers = props.getHeaders();
        headers = (headers == null) ? new HashMap<String, Object>() : new HashMap<>(headers);

        TRACER.getHttpTextFormat().inject(span.getContext(), headers, SETTER);

        props =
            new AMQP.BasicProperties(
                props.getContentType(),
                props.getContentEncoding(),
                headers,
                props.getDeliveryMode(),
                props.getPriority(),
                props.getCorrelationId(),
                props.getReplyTo(),
                props.getExpiration(),
                props.getMessageId(),
                props.getTimestamp(),
                props.getType(),
                props.getUserId(),
                props.getAppId(),
                props.getClusterId());
      }
    }
  }

  public static class ChannelGetAdvice {
    @Advice.OnMethodEnter
    public static long takeTimestamp(@Advice.Local("callDepth") int callDepth) {

      callDepth = CallDepthThreadLocalMap.incrementCallDepth(Channel.class);
      return System.currentTimeMillis();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void extractAndStartSpan(
        @Advice.This final Channel channel,
        @Advice.Argument(0) final String queue,
        @Advice.Enter final long startTime,
        @Advice.Local("callDepth") final int callDepth,
        @Advice.Return final GetResponse response,
        @Advice.Thrown final Throwable throwable) {

      if (callDepth > 0) {
        return;
      }
      CallDepthThreadLocalMap.reset(Channel.class);

      // can't create span and put into scope in method enter above, because can't add links after
      // span creation
      final Span.Builder spanBuilder =
          TRACER
              .spanBuilder("amqp.command")
              .setSpanKind(CLIENT)
              .setStartTimestamp(TimeUnit.MILLISECONDS.toNanos(startTime));

      if (response != null && response.getProps() != null) {
        final Map<String, Object> headers = response.getProps().getHeaders();

        if (headers != null) {
          SpanContext spanContext = null;
          try {
            spanContext = TRACER.getHttpTextFormat().extract(headers, GETTER);
          } catch (final IllegalArgumentException e) {
            // couldn't extract a context
          }
          if (spanContext != null) {
            if (TRACER.getCurrentSpan().getContext().isValid()) {
              spanBuilder.addLink(spanContext);
            } else {
              spanBuilder.setParent(spanContext);
            }
          }
        }
      }

      final Connection connection = channel.getConnection();

      final Span span = spanBuilder.startSpan();
      if (response != null) {
        span.setAttribute("message.size", response.getBody().length);
      }
      span.setAttribute(Tags.PEER_PORT, connection.getPort());
      try (final Scope scope = TRACER.withSpan(span)) {
        CONSUMER_DECORATE.afterStart(span);
        CONSUMER_DECORATE.onGet(span, queue);
        CONSUMER_DECORATE.onPeerConnection(span, connection.getAddress());
        CONSUMER_DECORATE.onError(span, throwable);
        CONSUMER_DECORATE.beforeFinish(span);
      } finally {
        span.end();
      }
    }
  }

  public static class ChannelConsumeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapConsumer(
        @Advice.Argument(0) final String queue,
        @Advice.Argument(value = 6, readOnly = false) Consumer consumer) {
      // We have to save off the queue name here because it isn't available to the consumer later.
      if (consumer != null && !(consumer instanceof TracedDelegatingConsumer)) {
        consumer = new TracedDelegatingConsumer(queue, consumer);
      }
    }
  }
}