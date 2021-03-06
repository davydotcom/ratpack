/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.groovy;

import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableMap;
import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import groovy.lang.GroovySystem;
import groovy.xml.MarkupBuilder;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.CharsetUtil;
import ratpack.api.Nullable;
import ratpack.file.FileSystemBinding;
import ratpack.func.Action;
import ratpack.func.Function;
import ratpack.groovy.guice.GroovyBindingsSpec;
import ratpack.groovy.guice.internal.DefaultGroovyBindingsSpec;
import ratpack.groovy.handling.GroovyChain;
import ratpack.groovy.handling.GroovyContext;
import ratpack.groovy.handling.internal.ClosureBackedHandler;
import ratpack.groovy.handling.internal.DefaultGroovyChain;
import ratpack.groovy.handling.internal.DefaultGroovyContext;
import ratpack.groovy.handling.internal.GroovyDslChainActionTransformer;
import ratpack.groovy.internal.*;
import ratpack.groovy.internal.GroovyVersionCheck;
import ratpack.groovy.script.ScriptNotFoundException;
import ratpack.groovy.template.Markup;
import ratpack.groovy.template.MarkupTemplate;
import ratpack.groovy.template.TextTemplate;
import ratpack.guice.Guice;
import ratpack.handling.Chain;
import ratpack.handling.Context;
import ratpack.handling.Handler;
import ratpack.handling.internal.ChainBuilders;
import ratpack.http.internal.HttpHeaderConstants;
import ratpack.registry.Registry;
import ratpack.server.RatpackServer;
import ratpack.server.ServerConfig;
import ratpack.server.internal.BaseDirFinder;
import ratpack.server.internal.FileBackedReloadInformant;
import ratpack.util.internal.IoUtils;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static ratpack.util.ExceptionUtils.uncheck;

/**
 * Static methods for specialized Groovy integration.
 */
public abstract class Groovy {

  private Groovy() {

  }

  /**
   * Starts a Ratpack app, defined by the given closure.
   * <p>
   * This method is used in Ratpack scripts as the entry point.
   * <pre class="groovy-ratpack-dsl">
   * import ratpack.session.SessionModule
   * import static ratpack.groovy.Groovy.*
   *
   * ratpack {
   *   bindings {
   *     // example of registering a module
   *     add(new SessionModule())
   *   }
   *   handlers {
   *     // define the application handlers
   *     get("foo") {
   *       render "bar"
   *     }
   *   }
   * }
   * </pre>
   * <h3>Standalone Scripts</h3>
   * <p>
   * This method can be used by standalone scripts to start a Ratpack application.
   * That is, you could save the above content in a file named “{@code ratpack.groovy}” (the name is irrelevant),
   * then start the application by running `{@code groovy ratpack.groovy}` on the command line.
   * <h3>Full Applications</h3>
   * <p>
   * It's also possible to build Groovy Ratpack applications with a traditional class based entry point.
   * The {@link ratpack.groovy.GroovyRatpackMain} class provides such an entry point.
   * In such a mode, a script like above is still used to define the application, but the script is no longer the entry point.
   * Ratpack will manage the compilation and execution of the script internally.
   *
   * @param closure The definition closure, delegating to {@link ratpack.groovy.Groovy.Ratpack}
   */
  public static void ratpack(@DelegatesTo(value = Ratpack.class, strategy = Closure.DELEGATE_FIRST) Closure<?> closure) {
    try {
      RatpackScriptBacking.execute(closure);
    } catch (Exception e) {
      throw uncheck(e);
    }
  }

  /**
   * The definition of a Groovy Ratpack application.
   *
   * @see ratpack.groovy.Groovy#ratpack(groovy.lang.Closure)
   */
  public static interface Ratpack {

    /**
     * Registers the closure used to configure the {@link ratpack.groovy.guice.GroovyBindingsSpec} that will back the application.
     *
     * @param configurer The configuration closure, delegating to {@link ratpack.groovy.guice.GroovyBindingsSpec}
     */
    void bindings(@DelegatesTo(value = GroovyBindingsSpec.class, strategy = Closure.DELEGATE_FIRST) Closure<?> configurer);

    /**
     * Registers the closure used to build the handler chain of the application.
     *
     * @param configurer The configuration closure, delegating to {@link GroovyChain}
     */
    void handlers(@DelegatesTo(value = GroovyChain.class, strategy = Closure.DELEGATE_FIRST) Closure<?> configurer);

    /**
     * Registers the closure used to build the configuration of the server.
     *
     * @param configurer The configuration closure, delegating to {@link ratpack.server.ServerConfig.Builder}
     */
    void serverConfig(@DelegatesTo(value = ServerConfig.Builder.class, strategy = Closure.DELEGATE_FIRST) Closure<?> configurer);

  }

  public static abstract class Script {

    public static final String DEFAULT_HANDLERS_PATH = "handlers.groovy";
    public static final String DEFAULT_BINDINGS_PATH = "bindings.groovy";
    public static final String DEFAULT_APP_PATH = "ratpack.groovy";

    private Script() {
    }

    public static void checkGroovy() {
      GroovyVersionCheck.ensureRequiredVersionUsed(GroovySystem.getVersion());
    }

    public static Function<? super RatpackServer.Definition.Builder, ? extends RatpackServer.Definition> app() {
      return app(false);
    }

    public static Function<? super RatpackServer.Definition.Builder, ? extends RatpackServer.Definition> app(boolean staticCompile) {
      return app(staticCompile, DEFAULT_APP_PATH, DEFAULT_APP_PATH.substring(0, 1).toUpperCase() + DEFAULT_APP_PATH.substring(1));
    }

    public static Function<? super RatpackServer.Definition.Builder, ? extends RatpackServer.Definition> app(Path script) {
      return b -> doApp(b, false, script.getParent(), script);
    }

    public static Function<? super RatpackServer.Definition.Builder, ? extends RatpackServer.Definition> app(boolean staticCompile, String... scriptPaths) {
      return b -> {
        String workingDir = StandardSystemProperty.USER_DIR.value();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        BaseDirFinder.Result baseDirResult = Arrays.stream(scriptPaths)
          .map(scriptPath -> BaseDirFinder.find(workingDir, classLoader, scriptPath))
          .filter(Optional::isPresent)
          .map(Optional::get)
          .findFirst()
          .orElseThrow(() -> new ScriptNotFoundException(scriptPaths));

        Path baseDir = baseDirResult.getBaseDir();
        Path scriptFile = baseDirResult.getResource();
        return doApp(b, staticCompile, baseDir, scriptFile);
      };
    }

    private static RatpackServer.Definition doApp(RatpackServer.Definition.Builder definition, boolean staticCompile, Path baseDir, Path scriptFile) throws Exception {
      String script = IoUtils.read(UnpooledByteBufAllocator.DEFAULT, scriptFile).toString(CharsetUtil.UTF_8);

      RatpackDslClosures closures = new FullRatpackDslCapture(staticCompile).apply(scriptFile, script);
      definition.config(ClosureUtil.configureDelegateFirstAndReturn(loadPropsIfPresent(ServerConfig.baseDir(baseDir), baseDir), closures.getConfig()));

      definition.registry(r -> {
        return Guice.registry(bindingsSpec -> {
          bindingsSpec.bindInstance(new FileBackedReloadInformant(scriptFile));
          ClosureUtil.configureDelegateFirst(new DefaultGroovyBindingsSpec(bindingsSpec), closures.getBindings());
        }).apply(r);
      });

      return definition.handler(r -> {
        return Groovy.chain(r, closures.getHandlers());
      });
    }

    private static ServerConfig.Builder loadPropsIfPresent(ServerConfig.Builder serverConfigBuilder, Path baseDir) {
      Path propsFile = baseDir.resolve(ServerConfig.Builder.DEFAULT_PROPERTIES_FILE_NAME);
      if (Files.exists(propsFile)) {
        serverConfigBuilder.props(propsFile);
      }
      return serverConfigBuilder;
    }

    public static Function<Registry, Handler> handlers() {
      return handlers(false);
    }

    public static Function<Registry, Handler> handlers(boolean staticCompile) {
      return handlers(staticCompile, DEFAULT_HANDLERS_PATH);
    }

    public static Function<Registry, Handler> handlers(boolean staticCompile, String scriptPath) {
      checkGroovy();
      return r -> {
        Path scriptFile = r.get(FileSystemBinding.class).file(scriptPath);
        boolean development = r.get(ServerConfig.class).isDevelopment();
        return new ScriptBackedHandler(scriptFile, development, new RatpackDslCapture<>(staticCompile, HandlersOnly::new, h ->
          Groovy.chain(r, h.handlers)
        ));
      };
    }

    public static Function<Registry, Registry> bindings() {
      return bindings(false);
    }

    public static Function<Registry, Registry> bindings(boolean staticCompile) {
      return bindings(staticCompile, DEFAULT_BINDINGS_PATH);
    }

    public static Function<Registry, Registry> bindings(boolean staticCompile, String scriptPath) {
      checkGroovy();
      return r -> {
        Path scriptFile = r.get(FileSystemBinding.class).file(scriptPath);
        String script = IoUtils.read(UnpooledByteBufAllocator.DEFAULT, scriptFile).toString(CharsetUtil.UTF_8);
        Closure<?> bindingsClosure = new RatpackDslCapture<>(staticCompile, BindingsOnly::new, b -> b.bindings).apply(scriptFile, script);
        return Guice.registry(bindingsSpec -> {
          bindingsSpec.bindInstance(new FileBackedReloadInformant(scriptFile));
          ClosureUtil.configureDelegateFirst(new DefaultGroovyBindingsSpec(bindingsSpec), bindingsClosure);
        }).apply(r);
      };
    }
  }

  private static class HandlersOnly implements Ratpack {
    private Closure<?> handlers = ClosureUtil.noop();

    @Override
    public void bindings(Closure<?> configurer) {
      throw new IllegalStateException("bindings {} not supported for this script");
    }

    @Override
    public void handlers(Closure<?> configurer) {
      this.handlers = configurer;
    }

    @Override
    public void serverConfig(Closure<?> configurer) {
      throw new IllegalStateException("serverConfig {} not supported for this script");
    }
  }

  private static class BindingsOnly implements Ratpack {
    private Closure<?> bindings = ClosureUtil.noop();

    @Override
    public void bindings(Closure<?> configurer) {
      this.bindings = configurer;
    }

    @Override
    public void handlers(Closure<?> configurer) {
      throw new IllegalStateException("handlers {} not supported for this script");
    }

    @Override
    public void serverConfig(Closure<?> configurer) {
      throw new IllegalStateException("serverConfig {} not supported for this script");
    }
  }

  /**
   * Builds a handler chain, with no backing registry.
   *
   * @param serverConfig The application server config
   * @param closure The chain definition
   * @return A handler
   * @throws Exception any exception thrown by the given closure
   */
  public static Handler chain(ServerConfig serverConfig, @DelegatesTo(value = GroovyChain.class, strategy = Closure.DELEGATE_FIRST) Closure<?> closure) throws Exception {
    return chain(serverConfig, null, closure);
  }

  /**
   * Creates a specialized Groovy context.
   *
   * @param context The context to convert to a Groovy context
   * @return The original context wrapped in a Groovy context
   */
  public static GroovyContext context(Context context) {
    return context instanceof GroovyContext ? (GroovyContext) context : new DefaultGroovyContext(context);
  }

  /**
   * Builds a chain, backed by the given registry.
   *
   * @param serverConfig The application server config
   * @param registry The registry.
   * @param closure The chain building closure.
   * @return A handler
   * @throws Exception any exception thrown by the given closure
   */
  public static Handler chain(@Nullable ServerConfig serverConfig, @Nullable Registry registry, @DelegatesTo(value = GroovyChain.class, strategy = Closure.DELEGATE_FIRST) Closure<?> closure) throws Exception {
    return ChainBuilders.build(
      serverConfig != null && serverConfig.isDevelopment(),
      new GroovyDslChainActionTransformer(serverConfig, registry),
      new ClosureInvoker<Object, GroovyChain>(closure).toAction(registry, Closure.DELEGATE_FIRST)
    );
  }

  /**
   * Builds a chain, backed by the given registry.
   *
   * @param registry The registry.
   * @param closure The chain building closure.
   * @return A handler
   * @throws Exception any exception thrown by the given closure
   */
  public static Handler chain(Registry registry, @DelegatesTo(value = GroovyChain.class, strategy = Closure.DELEGATE_FIRST) Closure<?> closure) throws Exception {
    return chain(registry.get(ServerConfig.class), registry, closure);
  }

  /**
   * Creates a chain action based on the given closure.
   *
   * @param closure The chain building closure.
   * @return A chain action
   * @throws Exception any exception thrown by the given closure
   */
  public static Action<Chain> chainAction(@DelegatesTo(value = GroovyChain.class, strategy = Closure.DELEGATE_FIRST) final Closure<?> closure) throws Exception {
    return chain -> ClosureUtil.configureDelegateFirst(new DefaultGroovyChain(chain), closure);
  }

  /**
   * Creates a {@link ratpack.handling.Context#render(Object) renderable} Groovy based template, using no model and the default content type.
   *
   * @param id The id/name of the template
   * @return a template
   */
  public static TextTemplate groovyTemplate(String id) {
    return groovyTemplate(id, null);
  }

  /**
   * Creates a {@link ratpack.handling.Context#render(Object) renderable} Groovy based markup template, using no model and the default content type.
   *
   * @param id The id/name of the template
   * @return a template
   */
  public static MarkupTemplate groovyMarkupTemplate(String id) {
    return groovyMarkupTemplate(id, (String) null);
  }

  /**
   * Creates a {@link ratpack.handling.Context#render(Object) renderable} Groovy based template, using no model.
   *
   * @param id The id/name of the template
   * @param type The content type of template
   * @return a template
   */
  public static TextTemplate groovyTemplate(String id, String type) {
    return groovyTemplate(ImmutableMap.<String, Object>of(), id, type);
  }

  /**
   * Creates a {@link ratpack.handling.Context#render(Object) renderable} Groovy based markup template, using no model.
   *
   * @param id The id/name of the template
   * @param type The content type of template
   * @return a template
   */
  public static MarkupTemplate groovyMarkupTemplate(String id, String type) {
    return groovyMarkupTemplate(ImmutableMap.<String, Object>of(), id, type);
  }

  /**
   * Creates a {@link ratpack.handling.Context#render(Object) renderable} Groovy based template, using the default content type.
   *
   * @param model The template model
   * @param id The id/name of the template
   * @return a template
   */
  public static TextTemplate groovyTemplate(Map<String, ?> model, String id) {
    return groovyTemplate(model, id, null);
  }

  /**
   * Creates a {@link ratpack.handling.Context#render(Object) renderable} Groovy based markup template, using the default content type.
   *
   * @param model The template model
   * @param id The id/name of the template
   * @return a template
   */
  public static MarkupTemplate groovyMarkupTemplate(Map<String, ?> model, String id) {
    return groovyMarkupTemplate(model, id, null);
  }

  /**
   * Creates a {@link ratpack.handling.Context#render(Object) renderable} Groovy based markup template, using the default content type.
   *
   * @param id the id/name of the template
   * @param modelBuilder an action the builds a model map
   * @return a template
   */
  public static MarkupTemplate groovyMarkupTemplate(String id, Action<? super ImmutableMap.Builder<String, Object>> modelBuilder) {
    return groovyMarkupTemplate(id, null, modelBuilder);
  }

  /**
   * Creates a {@link ratpack.handling.Context#render(Object) renderable} Groovy based markup template.
   *
   * @param id the id/name of the template
   * @param type The content type of template
   * @param modelBuilder an action the builds a model map
   * @return a template
   */
  public static MarkupTemplate groovyMarkupTemplate(String id, String type, Action<? super ImmutableMap.Builder<String, Object>> modelBuilder) {
    ImmutableMap<String, Object> model = uncheck(() -> Action.with(ImmutableMap.<String, Object>builder(), Action.noopIfNull(modelBuilder)).build());
    return groovyMarkupTemplate(model, id, type);
  }

  /**
   * Creates a {@link ratpack.handling.Context#render(Object) renderable} Groovy based template.
   *
   * @param model The template model
   * @param id The id/name of the template
   * @param type The content type of template
   * @return a template
   */
  public static TextTemplate groovyTemplate(Map<String, ?> model, String id, String type) {
    return new TextTemplate(model, id, type);
  }

  /**
   * Creates a {@link ratpack.handling.Context#render(Object) renderable} Groovy based template.
   *
   * @param model The template model
   * @param id The id/name of the template
   * @param type The content type of template
   * @return a template
   */
  public static MarkupTemplate groovyMarkupTemplate(Map<String, ?> model, String id, String type) {
    return new MarkupTemplate(id, type, model);
  }

  /**
   * Creates a handler instance from a closure.
   *
   * @param closure The closure to convert to a handler
   * @return The created handler
   */
  public static Handler groovyHandler(@DelegatesTo(value = GroovyContext.class, strategy = Closure.DELEGATE_FIRST) Closure<?> closure) {
    return new ClosureBackedHandler(closure);
  }

  /**
   * Immediately executes the given {@code closure} against the given {@code chain}, as a {@link GroovyChain}.
   *
   * @param chain the chain to add handlers to
   * @param closure the definition of handlers to add
   * @throws Exception any exception thrown by {@code closure}
   */
  public static void chain(Chain chain, @DelegatesTo(value = GroovyChain.class, strategy = Closure.DELEGATE_FIRST) Closure<?> closure) throws Exception {
    GroovyChain groovyChain = chain instanceof GroovyChain ? (GroovyChain) chain : new DefaultGroovyChain(chain);
    new ClosureInvoker<Object, GroovyChain>(closure).invoke(chain.getRegistry(), groovyChain, Closure.DELEGATE_FIRST);
  }

  /**
   * Creates a chain action implementation from the given closure.
   *
   * @param closure the definition of handlers to add
   * @throws Exception any exception thrown by {@code closure}
   * @return The created action
   */
  public static Action<Chain> chain(@DelegatesTo(value = GroovyChain.class, strategy = Closure.DELEGATE_FIRST) final Closure<?> closure) throws Exception {
    return (c) -> Groovy.chain(c, closure);
  }

  /**
   * Shorthand for {@link #markupBuilder(CharSequence, Charset, Closure)} with a content type of {@code "text/html"} and {@code "UTF-8"} encoding.
   *
   * @param closure The html definition
   * @return A renderable object (i.e. to be used with the {@link ratpack.handling.Context#render(Object)} method
   */
  public static Markup htmlBuilder(@DelegatesTo(value = MarkupBuilder.class, strategy = Closure.DELEGATE_FIRST) Closure<?> closure) {
    return markupBuilder(HttpHeaderConstants.HTML_UTF_8, CharsetUtil.UTF_8, closure);
  }

  /**
   * Renderable object for markup built using Groovy's {@link MarkupBuilder}.
   *
   * <pre class="groovy-chain-dsl">
   * import static ratpack.groovy.Groovy.markupBuilder
   *
   * get("some/path") {
   *   render markupBuilder("text/html", "UTF-8") {
   *     // MarkupBuilder DSL in here
   *   }
   * }
   * </pre>
   *
   * @param contentType The content type of the markup
   * @param encoding The character encoding of the markup
   * @param closure The definition of the markup
   * @return A renderable object (i.e. to be used with the {@link ratpack.handling.Context#render(Object)} method
   */
  public static Markup markupBuilder(CharSequence contentType, CharSequence encoding, @DelegatesTo(value = MarkupBuilder.class, strategy = Closure.DELEGATE_FIRST) Closure<?> closure) {
    return new Markup(contentType, Charset.forName(encoding.toString()), closure);
  }

  public static Markup markupBuilder(CharSequence contentType, Charset encoding, @DelegatesTo(value = MarkupBuilder.class, strategy = Closure.DELEGATE_FIRST) Closure<?> closure) {
    return new Markup(contentType, encoding, closure);
  }

}
