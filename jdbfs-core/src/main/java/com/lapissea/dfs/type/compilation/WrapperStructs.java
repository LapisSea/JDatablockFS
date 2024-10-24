package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.jorth.BytecodeUtils;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.TextUtil;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

public final class WrapperStructs{
	
	public abstract static class Wrapper<T> extends IOInstance.Managed<Wrapper<T>>{
		public abstract T get();
	}
	
	public record WrapperRes<T>(Struct<Wrapper<T>> struct, Function<T, Wrapper<T>> constructor){ }
	
	private static       ConcurrentHashMap<Class<?>, WrapperRes<?>> WRAPPER_STRUCT_CACHE = new ConcurrentHashMap<>();
	private static final Function<Class<?>, WrapperRes<?>>          generateWrapper      = WrapperStructs::generateWrapper;
	private static final Lock                                       GEN_LOCK             = new ReentrantLock();
	
	public static <T> boolean isWrapperType(Class<T> type){
		return FieldCompiler.getWrapperTypes().contains(type);
	}
	public static <T> WrapperRes<T> getWrapperStruct(Class<T> type){
		if(!isWrapperType(type)){
			return null;
		}
		try{
			//noinspection unchecked
			return (WrapperRes<T>)WRAPPER_STRUCT_CACHE.computeIfAbsent(type, generateWrapper);
		}catch(Throwable e){
			throw new RuntimeException("Failed to generate wrapper for type: " + type.getTypeName(), e);
		}
	}
	
	private static <T> WrapperRes<T> generateWrapper(Class<T> type){
		ConfigDefs.CompLogLevel.SMALL.log("Generated wrapper for {}#purple", type.getTypeName());
		var typeName  = makeTypeName(type);
		var className = WrapperStructs.class.getPackageName() + "." + Wrapper.class.getSimpleName() + "€" + typeName;
		try{
			var file = Jorth.generateClass(FieldCompiler.class.getClassLoader(), className, writer -> {
				writer.addImportAs(Wrapper.class, "Wrapper");
				writer.addImports(IOValue.class, Override.class, TextUtil.class);
				writer.write(
					"""
						extends #Wrapper<{!0}>
						public final class {!0} start
							@ #IOValue
							private field val {1}
							
							public function <init> start
								super start end
							end
							
							public function <init>
								arg val {1}
							start
								super start end
								get #arg val
								set this val
							end
							
							public function get
								returns #Object
							start
								get this val
							end
							
							@ #Override
							public function toString
								returns #String
							start
								new #StringBuilder
								call append start 'WrapperOf€{!2}{' end
								call append start
									static call #TextUtil toString start
										get this val
									end
								end
								call append start '}' end
								call toString
							end
						end
						""",
					className, type, typeName
				);
			});
			
			var lookup = MethodHandles.privateLookupIn(WrapperStructs.class, MethodHandles.lookup());
			try{
				//noinspection unchecked
				var wrapper = (Class<Wrapper<T>>)lookup.defineClass(file);
				
				var struct = Struct.of(wrapper);
				
				var ctor = wrapper.getConstructor(type);
				
				return new WrapperRes<>(
					struct,
					Access.makeLambda(ctor, MethodHandles.privateLookupIn(wrapper, lookup), Function.class)
				);
			}catch(Throwable e){
				Log.warn("Failed to generate {}#red\nBytecode:\n{}", className, (Supplier<?>)() -> BytecodeUtils.classToString(file));
				throw e;
			}
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to write jorth for " + typeName, e);
		}catch(IllegalAccessException|NoSuchMethodException e){
			throw new RuntimeException("Failed to create class for " + typeName, e);
		}
	}
	
	private static String makeTypeName(Class<?> type){
		int dims = 0;
		var cl   = type;
		while(cl.isArray()){
			dims++;
			cl = cl.componentType();
		}
		
		return cl.getSimpleName() + "_arr".repeat(dims);
	}
}
