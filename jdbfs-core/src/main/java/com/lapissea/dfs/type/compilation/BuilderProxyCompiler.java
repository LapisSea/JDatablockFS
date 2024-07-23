package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.jorth.BytecodeUtils;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;

import java.util.concurrent.ConcurrentHashMap;

public final class BuilderProxyCompiler{
	
	public abstract static class Builder<Actual extends IOInstance<Actual>> extends IOInstance.Managed<Builder<Actual>>{
		public abstract Actual build();
	}
	
	public static final String BUILDER_PROXY_POSTFIX = "€Builder";
	
	private static final ConcurrentHashMap<Struct<?>, Class<Builder<?>>> CACHE = new ConcurrentHashMap<>();
	
	public static <T extends IOInstance<T>> Class<Builder<T>> getProxy(Class<T> type){ return getProxy(Struct.of(type)); }
	public static <T extends IOInstance<T>> Class<Builder<T>> getProxy(Struct<T> type){
		//noinspection unchecked,rawtypes
		return (Class)CACHE.computeIfAbsent(type, s -> (Class)compileProxy(type));
	}
	
	private static <T extends IOInstance<T>> Class<Builder<T>> compileProxy(Struct<T> type){
		if(!type.needsBuilderObj()){
			throw new IllegalArgumentException();
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
					Builder.class, proxyName);
				
				for(IOField<T, ?> field : type.getFields()){
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
					baseClass, type.getFields(), IOInstance.class);
				
				writer.write("end");
			}, log);
			
			ClassGenerationCommons.dumpClassName(proxyName, clazzBytes);
			if(log != null){
				Log.log("Generated jorth:\n" + log.output());
				BytecodeUtils.printClass(clazzBytes);
			}
			
			//noinspection unchecked
			var completed = (Class<Builder<T>>)Access.privateLookupIn(baseClass)
			                                         .defineClass(clazzBytes);
			Log.trace("Generated builder: {}#yellow", proxyName);
			return completed;
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
