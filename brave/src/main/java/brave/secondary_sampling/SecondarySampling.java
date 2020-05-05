/*
 * Copyright 2019-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.secondary_sampling;

import brave.Tracing;
import brave.TracingCustomizer;
import brave.http.HttpRequest;
import brave.http.HttpTracing;
import brave.http.HttpTracingCustomizer;
import brave.internal.MapPropagationFields;
import brave.internal.Nullable;
import brave.internal.PropagationFieldsFactory;
import brave.propagation.Propagation;
import brave.propagation.Propagation.KeyFactory;
import brave.propagation.TraceContext;
import brave.rpc.RpcRequest;
import brave.rpc.RpcTracing;
import brave.rpc.RpcTracingCustomizer;
import brave.sampler.SamplerFunction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * This is a <a href="https://github.com/openzipkin-contrib/zipkin-secondary-sampling/tree/master/docs/design.md">Secondary
 * Sampling</a> proof of concept.
 */
public final class SecondarySampling
  extends Propagation.Factory
  implements TracingCustomizer, HttpTracingCustomizer, RpcTracingCustomizer {
  static final ExtraFactory EXTRA_FACTORY = new ExtraFactory();

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    String fieldName = "sampling", tagName = "sampled_keys";
    Propagation.Factory propagationFactory;
    SecondaryProvisioner provisioner = new SecondaryProvisioner() {
      @Override public void provision(Object request, Callback callback) {
      }

      @Override public String toString() {
        return "NoopSecondarySamplingStateProvisioner()";
      }
    };
    @Nullable SamplerFunction<HttpRequest> httpServerSampler;
    @Nullable SamplerFunction<RpcRequest> rpcServerSampler;
    SecondarySampler secondarySampler;

    /** Optional: The ascii lowercase propagation field name to use. Defaults to {@code sampling}. */
    public Builder fieldName(String fieldName) {
      this.fieldName = validateAndLowercase(fieldName, "field");
      return this;
    }

    /** Optional: The ascii lowercase tag name to use. Defaults to {@code sampled_keys}. */
    public Builder tagName(String tagName) {
      this.tagName = validateAndLowercase(tagName, "tag");
      return this;
    }

    /**
     * Required: This will override any value passed to {@link Tracing.Builder#propagationFactory(brave.propagation.Propagation.Factory)}
     *
     * <p>This controls the primary propagation mechanism.
     */
    public Builder propagationFactory(Propagation.Factory propagationFactory) {
      if (propagationFactory == null) throw new NullPointerException("propagationFactory == null");
      this.propagationFactory = propagationFactory;
      return this;
    }

    /**
     * Optional: This will override what is passed to {@link HttpTracing.Builder#serverSampler(SamplerFunction)}
     *
     * <p>This controls the primary sampling mechanism for HTTP server requests. This control is
     * present here for convenience only, as it can be done externally with {@link
     * HttpTracingCustomizer}.
     */
    public Builder httpServerSampler(SamplerFunction<HttpRequest> httpServerSampler) {
      if (httpServerSampler == null) throw new NullPointerException("httpServerSampler == null");
      this.httpServerSampler = httpServerSampler;
      return this;
    }

    /**
     * Optional: This will override what is passed to {@link RpcTracing.Builder#serverSampler(SamplerFunction)}
     *
     * <p>This controls the primary sampling mechanism for RPC server requests. This control is
     * present here for convenience only, as it can be done externally with {@link
     * RpcTracingCustomizer}.
     */
    public Builder rpcServerSampler(SamplerFunction<RpcRequest> rpcServerSampler) {
      if (rpcServerSampler == null) throw new NullPointerException("rpcServerSampler == null");
      this.rpcServerSampler = rpcServerSampler;
      return this;
    }

    /**
     * Optional: Parses a request to provision new secondary sampling keys prior to {@link
     * #secondarySampler(SecondarySampler) sampling existing ones}.
     *
     * <p>By default, this node will only participate in existing keys, it will not create new
     * sampling keys.
     */
    public Builder provisioner(SecondaryProvisioner provisioner) {
      if (provisioner == null) throw new NullPointerException("provisioner == null");
      this.provisioner = provisioner;
      return this;
    }

    /**
     * Required: Performs secondary sampling before primary {@link #httpServerSampler(SamplerFunction)
     * HTTP} and {@link #rpcServerSampler(SamplerFunction) RPC} sampling.
     */
    public Builder secondarySampler(SecondarySampler secondarySampler) {
      if (secondarySampler == null) throw new NullPointerException("secondarySampler == null");
      this.secondarySampler = secondarySampler;
      return this;
    }

    public SecondarySampling build() {
      if (propagationFactory == null) throw new NullPointerException("propagationFactory == null");
      if (secondarySampler == null) throw new NullPointerException("secondarySampler == null");
      return new SecondarySampling(this);
    }

    Builder() {
    }
  }

  final String fieldName, tagName;
  final Propagation.Factory delegate;
  final SecondaryProvisioner provisioner;
  // TODO: it may be possible to make a parameterized primary sampler that just checks the input
  // type instead of having a separate type for http, rpc etc.
  @Nullable final SamplerFunction<HttpRequest> httpServerSampler;
  @Nullable final SamplerFunction<RpcRequest> rpcServerSampler;
  final SecondarySampler secondarySampler;

  SecondarySampling(Builder builder) {
    this.fieldName = builder.fieldName;
    this.tagName = builder.tagName;
    this.delegate = builder.propagationFactory;
    this.provisioner = builder.provisioner;
    this.httpServerSampler = builder.httpServerSampler;
    this.rpcServerSampler = builder.rpcServerSampler;
    this.secondarySampler = builder.secondarySampler;
  }

  @Override public boolean supportsJoin() {
    return delegate.supportsJoin();
  }

  @Override public <K> Propagation<K> create(KeyFactory<K> keyFactory) {
    if (keyFactory == null) throw new NullPointerException("keyFactory == null");
    return new Propagation<>(delegate.create(keyFactory), keyFactory.create(fieldName), this);
  }

  @Override public boolean requires128BitTraceId() {
    return delegate.requires128BitTraceId();
  }

  @Override public TraceContext decorate(TraceContext context) {
    TraceContext result = delegate.decorate(context);
    return EXTRA_FACTORY.decorate(result);
  }

  @Override public void customize(Tracing.Builder builder) {
    builder.addFinishedSpanHandler(new SecondarySamplingSpanHandler(tagName))
      .propagationFactory(this)
      .alwaysReportSpans();
  }

  @Override public void customize(HttpTracing.Builder builder) {
    if (httpServerSampler != null) builder.serverSampler(httpServerSampler);
  }

  @Override public void customize(RpcTracing.Builder builder) {
    if (rpcServerSampler != null) builder.serverSampler(rpcServerSampler);
  }

  static final class ExtraFactory
    extends PropagationFieldsFactory<SecondarySamplingState, Boolean, Extra> {
    @Override public Class<Extra> type() {
      return Extra.class;
    }

    @Override protected Extra create() {
      return new Extra();
    }

    @Override protected Extra create(Extra parent) {
      return new Extra(parent);
    }

    Extra create(Map<SecondarySamplingState, Boolean> initial) {
      return initial.isEmpty() ? new Extra() : new Extra(initial);
    }
  }

  static final class Extra extends MapPropagationFields<SecondarySamplingState, Boolean> {
    Extra() {
    }

    // avoids copy-on-write for each keys on initial construction
    Extra(Map<SecondarySamplingState, Boolean> initial) {
      super(initial);
    }

    Extra(Extra parent) {
      super(parent);
    }
  }

  static class Propagation<K> implements brave.propagation.Propagation<K> {
    final brave.propagation.Propagation<K> delegate;
    final K samplingKey;
    final SecondarySampling secondarySampling;
    final List<K> keys;

    Propagation(brave.propagation.Propagation<K> delegate, K samplingKey,
      SecondarySampling secondarySampling) {
      this.delegate = delegate;
      this.samplingKey = samplingKey;
      this.secondarySampling = secondarySampling;
      ArrayList<K> keys = new ArrayList<>(delegate.keys());
      keys.add(samplingKey);
      this.keys = Collections.unmodifiableList(keys);
    }

    @Override public List<K> keys() {
      return keys;
    }

    @Override public <R> TraceContext.Injector<R> injector(Setter<R, K> setter) {
      if (setter == null) throw new NullPointerException("setter == null");
      return new SecondarySamplingInjector<>(this, setter);
    }

    @Override public <R> TraceContext.Extractor<R> extractor(Getter<R, K> getter) {
      if (getter == null) throw new NullPointerException("getter == null");
      return new SecondarySamplingExtractor<>(this, getter);
    }
  }

  static String validateAndLowercase(String name, String title) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException(name + " is not a valid " + title + " name");
    }
    return name.toLowerCase(Locale.ROOT);
  }
}
