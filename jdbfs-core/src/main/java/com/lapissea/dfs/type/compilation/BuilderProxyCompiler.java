package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.compilation.helpers.ProxyBuilder;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.utils.PerKeyLock;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.jorth.BytecodeUtils;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;

public final class BuilderProxyCompiler{
	
	public static final String BUILDER_PROXY_POSTFIX = "€Builder";
	
	private static final WeakKeyValueMap<Class<?>, Class<? extends ProxyBuilder<?>>> CACHE      = new WeakKeyValueMap.Sync<>();
	private static final PerKeyLock<Class<?>>                                        CACHE_LOCK = new PerKeyLock<>();
	
	public static <T extends IOInstance<T>> Class<ProxyBuilder<T>> getProxy(Class<T> type){ return getProxy(Struct.of(type)); }
	public static <T extends IOInstance<T>> Class<ProxyBuilder<T>> getProxy(Struct<T> type){
		return CACHE_LOCK.syncGet(type.getType(), () -> {
			var cls = type.getType();
			//noinspection unchecked
			var cached = (Class<ProxyBuilder<T>>)CACHE.get(cls);
			if(cached != null) return cached;
			
			var proxy = compileProxy(type);
			CACHE.put(cls, proxy);
			return proxy;
		});
	}
	
	private static <T extends IOInstance<T>> Class<ProxyBuilder<T>> compileProxy(Struct<T> type){
		if(!type.needsBuilderObj()){
			throw new IllegalArgumentException();
		}
		if(Log.TRACE){
			var typ = type.getType();
			Log.trace("Generating builder for: {}#yellow{}#yellowBright",
			          typ.getName().substring(0, typ.getName().length() - typ.getSimpleName().length()),
			          typ.getSimpleName());
		}
		
		var baseClass = type.getType();
		var proxyName = baseClass.getName() + BUILDER_PROXY_POSTFIX;
		
		try{
			var log = JorthLogger.make();
			var clazzBytes = Jorth.generateClass(baseClass.getClassLoader(), proxyName, writer -> {
				writer.write(
					"""
						extends {0} <{1}>
						public class {!1} start
						""",
					ProxyBuilder.class, proxyName);
				
				for(IOField<T, ?> field : type.getRealFields()){
					writeField(writer, field.getAccessor());
				}
				
				writer.write(
					"""
						public function build
							returns {2}
						start
							new {0} start
								template-for #field in {1} start
									get this #field.name
								end
							end
						end
						""",
					baseClass, type.getRealFields(), IOInstance.class);
				
				writer.wEnd();
			}, log);
			
			ClassGenerationCommons.dumpClassName(proxyName, clazzBytes);
			if(log != null){
				Log.log("Generated jorth:\n" + log.output());
				BytecodeUtils.printClass(clazzBytes);
			}
			
			//noinspection unchecked
			return (Class<ProxyBuilder<T>>)Access.privateLookupIn(baseClass)
			                                     .defineClass(clazzBytes);
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate proxy", e);
		}catch(IllegalAccessException e){
			throw new RuntimeException(e);
		}
	}
	
	private static void writeField(CodeStream writer, FieldAccessor<?> field) throws MalformedJorth{
		JorthUtils.writeAnnotations(writer, field.getAnnotations().values());
		writer.write("public field {!} {}", field.getName(), field.getGenericType(null));
		
	}
}
