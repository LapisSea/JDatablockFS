package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.compilation.helpers.ProxyBuilder;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.utils.PerKeyLock;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.jorth.BytecodeUtils;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.NotImplementedException;

import java.lang.invoke.MethodHandles;
import java.util.Objects;

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
		
		var baseClass     = type.getType();
		var concreteClass = type.getConcreteType();
		
		ConfigDefs.CompLogLevel.SMALL.log("Generating builder for: {}#yellow{}#yellowBright", Utils.classPathHeadless(baseClass), baseClass.getSimpleName());
		
		if(type.getRealFields().size()>1 && IOFieldTools.tryGetOrImplyOrder(type).isEmpty()){
			throw new MalformedStruct("fmt", "Structs with final fields need an {#yellowOrder#} annotation! {}#red does not have one. The order should match the order of fields in the constructor.", baseClass);
		}
		
		var proxyName = baseClass.getName() + BUILDER_PROXY_POSTFIX;
		
		try{
			var log = JorthLogger.make();
			var clazzBytes = Jorth.generateClass(concreteClass.getClassLoader(), proxyName, writer -> {
				var fields = type.getRealFields();
				writer.addImports(Struct.class, ChunkPointer.class);
				
				var parms = baseClass.getTypeParameters();
				for(var parm : parms){
					var bounds = parm.getBounds();
					if(bounds.length != 1){
						throw new NotImplementedException("Implement multi bound type variable");
					}
					writer.write("type-arg {!} {}", parm.getName(), bounds[0]);
				}
				
				writer.write(
					"""
						extends {0} <{1}>
						public final class {!1} start
						""",
					ProxyBuilder.class, proxyName);
				
				writer.write(
					"""
						private static field $V_STRUCT #Struct<{0}>
						
						private static function $STRUCT
							returns #Struct<{0}>
						start
							static call {1} isNull start
								get {0} $V_STRUCT
							end
							if start
								static call #Struct of start
									class {0}
								end
								set {0} $V_STRUCT
							end
							get {0} $V_STRUCT
						end
						
						function <clinit> start
							static call {2} allowFullAccess start
								static call {3} lookup
							end
						end
						""",
					proxyName, Objects.class, IOInstance.Managed.class, MethodHandles.class);
				
				for(IOField<T, ?> field : fields){
					writeField(writer, field.getAccessor());
				}
				writer.write(
					"""
						public function <init>
						start
							super start
								static call {0} $STRUCT
							end
							template-for #fName in {1} start
								get #ChunkPointer NULL
								set this #fName
							end
						end
						""",
					proxyName, fields.byType(ChunkPointer.class).map(IOField::getName));
				
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
					concreteClass, fields, IOInstance.class);
				
				writer.wEnd();
			}, log);
			
			ClassGenerationCommons.dumpClassName(proxyName, clazzBytes);
			if(log != null){
				Log.log("Generated jorth:\n" + log.output());
				BytecodeUtils.printClass(clazzBytes);
			}
			
			//noinspection unchecked
			return (Class<ProxyBuilder<T>>)Access.defineClass(concreteClass, clazzBytes);
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate proxy for " + baseClass.getTypeName(), e);
		}
	}
	
	private static void writeField(CodeStream writer, FieldAccessor<?> field) throws MalformedJorth{
		JorthUtils.writeAnnotations(writer, field.getAnnotations().values());
		writer.write("public field {!} {}", field.getName(), field.getGenericType(null));
		
	}
}
