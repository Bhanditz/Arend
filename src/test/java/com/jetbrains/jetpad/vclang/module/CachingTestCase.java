package com.jetbrains.jetpad.vclang.module;

import com.jetbrains.jetpad.vclang.error.DummyErrorReporter;
import com.jetbrains.jetpad.vclang.frontend.resolving.OneshotSourceInfoCollector;
import com.jetbrains.jetpad.vclang.frontend.storage.PreludeStorage;
import com.jetbrains.jetpad.vclang.module.caching.CacheLoadingException;
import com.jetbrains.jetpad.vclang.module.caching.CacheManager;
import com.jetbrains.jetpad.vclang.module.caching.CachePersistenceException;
import com.jetbrains.jetpad.vclang.module.caching.PersistenceProvider;
import com.jetbrains.jetpad.vclang.module.source.SimpleModuleLoader;
import com.jetbrains.jetpad.vclang.module.source.SourceId;
import com.jetbrains.jetpad.vclang.naming.NameResolverTestCase;
import com.jetbrains.jetpad.vclang.term.Abstract;
import com.jetbrains.jetpad.vclang.term.Prelude;
import com.jetbrains.jetpad.vclang.typechecking.TypecheckedReporter;
import com.jetbrains.jetpad.vclang.typechecking.TypecheckerState;
import com.jetbrains.jetpad.vclang.typechecking.Typechecking;
import com.jetbrains.jetpad.vclang.typechecking.order.BaseDependencyListener;
import org.junit.Before;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.util.*;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class CachingTestCase extends NameResolverTestCase {
  protected final MemoryStorage storage = new MemoryStorage(moduleNsProvider, nameResolver);
  protected final List<Abstract.Definition> typecheckingSucceeded = new ArrayList<>();
  protected final List<Abstract.Definition> typecheckingFailed = new ArrayList<>();
  protected SimpleModuleLoader<MemoryStorage.SourceId> moduleLoader;
  protected CacheManager<MemoryStorage.SourceId> cacheManager;
  protected TypecheckerState tcState;
  private OneshotSourceInfoCollector<MemoryStorage.SourceId> srcInfoCollector;
  private PersistenceProvider<MemoryStorage.SourceId> peristenceProvider = new MemoryPersistenceProvider<>();
  private Typechecking typechecking;

  @Before
  public void initialize() {
    srcInfoCollector = new OneshotSourceInfoCollector<>();
    moduleLoader = new SimpleModuleLoader<MemoryStorage.SourceId>(storage, errorReporter) {
      @Override
      public Abstract.ClassDefinition load(MemoryStorage.SourceId sourceId) {
        Abstract.ClassDefinition result = super.load(sourceId);
        if (result == null) {
          throw new IllegalStateException("Could not load module");
        }
        srcInfoCollector.visitModule(sourceId, result);
        return result;
      }
    };
    nameResolver.setModuleResolver(moduleLoader);
    cacheManager = new CacheManager<>(peristenceProvider, storage, srcInfoCollector.sourceInfoProvider);
    tcState = cacheManager.getTypecheckerState();
    typechecking = new Typechecking(tcState, staticNsProvider, dynamicNsProvider, errorReporter, new TypecheckedReporter() {
      @Override
      public void typecheckingSucceeded(Abstract.Definition definition) {
        typecheckingSucceeded.add(definition);
      }
      @Override
      public void typecheckingFailed(Abstract.Definition definition) {
        typecheckingFailed.add(definition);
      }
    }, new BaseDependencyListener());
  }

  @Override
  protected void loadPrelude() {
    final String preludeSource;
    try (Reader in = new InputStreamReader(Prelude.class.getResourceAsStream(PreludeStorage.SOURCE_RESOURCE_PATH), "UTF-8")) {
      StringBuilder builder = new StringBuilder();
      final char[] buffer = new char[1024 * 1024];
      for (;;) {
        int rsz = in.read(buffer, 0, buffer.length);
        if (rsz < 0)
          break;
        builder.append(buffer, 0, rsz);
      }
      preludeSource = builder.toString();
    } catch (IOException e) {
      throw new IllegalStateException();
    }

    storage.add(ModulePath.moduleName("Prelude"), preludeSource);
    MemoryStorage.SourceId sourceId = moduleLoader.locateModule(ModulePath.moduleName("Prelude"));

    prelude = moduleLoader.load(sourceId);
    storage.setPreludeNamespace(staticNsProvider.forDefinition(prelude));
    srcInfoCollector.visitModule(sourceId, prelude);

    new Typechecking(cacheManager.getTypecheckerState(), staticNsProvider, dynamicNsProvider, new DummyErrorReporter(), new Prelude.UpdatePreludeReporter(cacheManager.getTypecheckerState()), new BaseDependencyListener()).typecheckModules(Collections.singleton(this.prelude));
  }

  protected void typecheck(Abstract.ClassDefinition module) {
    typecheck(module, 0);
  }

  protected void typecheck(Abstract.ClassDefinition module, int size) {
    typechecking.typecheckModules(Collections.singleton(module));
    assertThat(errorList, size > 0 ? hasSize(size) : is(empty()));
  }

  protected void load(MemoryStorage.SourceId sourceId, Abstract.ClassDefinition classDefinition) {
    try {
      boolean loaded = cacheManager.loadCache(sourceId, classDefinition);
      assertThat(loaded, is(true));
    } catch (CacheLoadingException e) {
      throw new IllegalStateException();
    }
    assertThat(errorList, is(empty()));
  }

  protected void persist(MemoryStorage.SourceId sourceId) {
    try {
      boolean persisted = cacheManager.persistCache(sourceId);
      assertThat(persisted, is(true));
    } catch (CachePersistenceException e) {
      throw new IllegalStateException();
    }
    assertThat(errorList, is(empty()));
  }


  public static class MemoryPersistenceProvider<SourceIdT extends SourceId> implements PersistenceProvider<SourceIdT> {
    private final Map<String, Object> memMap = new HashMap<>();

    @Override
    public URI getUri(SourceIdT sourceId) {
      String key = remember(sourceId);
      return URI.create("memory://" + key);
    }

    @Override
    public SourceIdT getModuleId(URI sourceUrl) {
      if (!("memory".equals(sourceUrl.getScheme()))) throw new IllegalArgumentException();
      //noinspection unchecked
      return (SourceIdT) recall(sourceUrl.getHost());
    }

    @Override
    public String getIdFor(Abstract.Definition definition) {
      return remember(definition);
    }

    @Override
    public Abstract.Definition getFromId(SourceIdT sourceId, String id) {
      return (Abstract.Definition) recall(id);
    }

    private String remember(Object o) {
      String key = objectKey(o);
      Object prev = memMap.put(key, o);
      if (prev != null && !(prev.equals(o))) {
        throw new IllegalStateException();
      }
      return key;
    }

    private Object recall(String key) {
      return memMap.get(key);
    }

    private String objectKey(Object o) {
      return Integer.toString(System.identityHashCode(o));
    }
  }
}
