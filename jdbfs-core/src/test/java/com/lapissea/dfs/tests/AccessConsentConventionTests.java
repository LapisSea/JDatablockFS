package com.lapissea.dfs.tests;

import com.lapissea.dfs.internal.Access;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Convention-walk coverage. The consentwalk.* fixtures are loaded by a child-first loader, so they
 * sit in a fresh unnamed module with no consent and Access must discover the DFSAccessConsent anchor.
 */
public class AccessConsentConventionTests{
	
	private static ClassLoader isolated(){
		return new ClassLoader(AccessConsentConventionTests.class.getClassLoader()){
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
				if(!name.startsWith("consentwalk.")){
					return super.loadClass(name, resolve);
				}
				synchronized(this){
					var loaded = findLoadedClass(name);
					if(loaded == null){
						var path = name.replace('.', '/') + ".class";
						try(var in = AccessConsentConventionTests.class.getResourceAsStream("/" + path)){
							if(in == null) throw new ClassNotFoundException(name);
							var bytes = in.readAllBytes();
							loaded = defineClass(name, bytes, 0, bytes.length);
						}catch(IOException e){
							throw new ClassNotFoundException(name, e);
						}
					}
					if(resolve) resolveClass(loaded);
					return loaded;
				}
			}
		};
	}
	
	private static Class<?> load(String name, ClassLoader loader, boolean initialize) throws ClassNotFoundException{
		return Class.forName(name, initialize, loader);
	}
	
	@Test
	void conventionWalkGrantsConsent() throws Exception{
		var loader = isolated();
		var target = load("consentwalk.good.Target", loader, true);
		var field  = target.getDeclaredField("value");
		
		// Access must discover the DFSAccessConsent anchor for this fresh module.
		assertThat(Access.makeVarHandle(field)).isNotNull();
		assertThat(Access.findAccess(target, Access.Mode.PRIVATE, Access.Mode.MODULE)).isNotNull();
	}
	
	@Test
	void conventionWalkWithoutAnchorFails() throws Exception{
		var loader = isolated();
		var target = load("consentwalk.noanchor.Target", loader, true);
		var field  = target.getDeclaredField("value");
		
		assertThatThrownBy(() -> Access.makeVarHandle(field)).isInstanceOf(IllegalAccessException.class);
	}
	
	@Test
	void conventionWalkSkipsUnsafeAnchor() throws Exception{
		var loader = isolated();
		var target = load("consentwalk.bad.Target", loader, true);
		var field  = target.getDeclaredField("value");
		
		// consentwalk.bad.DFSAccessConsent leaks the lookup in a field, so it must be ignored.
		assertThatThrownBy(() -> Access.makeVarHandle(field)).isInstanceOf(IllegalAccessException.class);
	}
	
	@Test
	void explicitConsentModuleRejectsUnsafeAnchor() throws Exception{
		var loader = isolated();
		// initialize=false: keep the module unconsented so consentModule reaches the validation.
		var bad = load("consentwalk.bad.DFSAccessConsent", loader, false);
		
		assertThatThrownBy(() -> Access.consentModule(bad)).isInstanceOf(IllegalArgumentException.class);
	}
	
	@Test
	void explicitAnchorConsentsModule() throws Exception{
		var loader = isolated();
		var anchor = load("consentwalk.explicit.Consent", loader, false);
		var target = load("consentwalk.explicit.Target", loader, true);
		var field  = target.getDeclaredField("value");
		
		// No DFSAccessConsent sibling, so the walk cannot help: only the explicit anchor can.
		assertThatThrownBy(() -> Access.makeVarHandle(field)).isInstanceOf(IllegalAccessException.class);
		
		Access.consentModule(anchor);
		assertThat(Access.makeVarHandle(field)).isNotNull();
	}
}
