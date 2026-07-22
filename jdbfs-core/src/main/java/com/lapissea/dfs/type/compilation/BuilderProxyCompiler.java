package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.internal.AccessProvider;
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
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.jorth.redo.ClassDefinition;
import com.lapissea.jorth.redo.FieldDefinition;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;

import java.util.Objects;

public final class BuilderProxyCompiler{
	
	public static final String BUILDER_PROXY_POSTFIX = "€Builder";
	
	private static final WeakKeyValueMap<Class<?>, Class<? extends ProxyBuilder<?>>> CACHE      = new WeakKeyValueMap.Sync<>();
	private static final PerKeyLock<Class<?>>                                        CACHE_LOCK = new PerKeyLock<>();
	
	public static <T extends IOInstance<T>> Class<ProxyBuilder<T>> getProxy(Class<T> type) throws IllegalAccessException{ return getProxy(Struct.of(type)); }
	public static <T extends IOInstance<T>> Class<ProxyBuilder<T>> getProxy(Struct<T> type) throws IllegalAccessException{
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
	
	private static <T extends IOInstance<T>> Class<ProxyBuilder<T>> compileProxy(Struct<T> type) throws IllegalAccessException{
		if(!type.needsBuilderObj()){
			throw new IllegalArgumentException();
		}
		
		var baseClass     = type.getType();
		var concreteClass = type.getConcreteType();
		
		
		AccessProvider concreteClassAccess = Access.findAccess(concreteClass, Access.Mode.PACKAGE);
		
		ConfigDefs.CompLogLevel.SMALL.log("Generating builder for: {}#yellow{}#yellowBright", Utils.classPathHeadless(baseClass), baseClass.getSimpleName());
		
		if(type.getRealFields().size()>1 && IOFieldTools.tryGetOrImplyOrder(type).isEmpty()){
			throw new MalformedStruct("fmt", "Structs with final fields need an {#yellowOrder#} annotation! {}#red does not have one. The order should match the order of fields in the constructor.", baseClass);
		}
		
		var proxyName = baseClass.getName() + BUILDER_PROXY_POSTFIX;
		
		try{
			var fields = type.getRealFields();
			
			var cw = new ClassDefinition(concreteClass.getClassLoader());
			
			var parms = baseClass.getTypeParameters();
			for(var parm : parms){
				var bounds = parm.getBounds();
				if(bounds.length != 1){
					throw new NotImplementedException("Implement multi bound type variable");
				}
				cw.genericArg(bounds[0], parm.getName());
			}
			var proxyCName = ClassName.dotted(proxyName);
			cw.extendsType(GenericType.of(ProxyBuilder.class).withArgs(proxyCName))
			  .name(proxyCName)
			  .visibility(Visibility.PUBLIC).finalAcc();
			
			var structType = GenericType.of(Struct.class).withArgs(proxyCName);
			cw.field(structType, "$V_STRUCT").staticAcc().visibility(Visibility.PRIVATE);
			
			cw.function("$STRUCT").returns(structType).staticAcc().visibility(Visibility.PRIVATE)
			  .body()
			  .call(Objects.class, "isNull", e -> e.get(proxyCName, "$V_STRUCT"))
			  .ifTrue(block -> {
				  block.call(Struct.class, "of", a -> a.val(proxyCName))
				       .set(proxyCName, "$V_STRUCT");
			  })
			  .get(proxyCName, "$V_STRUCT");
			
			for(IOField<T, ?> field : fields){
				writeField(cw, field.getAccessor());
			}
			
			var init = cw.instanceInit()
			             .body()
			             .callSuper(args -> args.call(proxyCName, "$STRUCT"));
			
			for(String fName : fields.byType(ChunkPointer.class).map(IOField::getName)){
				init.get(ChunkPointer.class, "NULL")
				    .setThis(fName);
			}
			
			cw.function("build").returns(IOInstance.class)
			  .body()
			  .newObj(concreteClass, args -> {
				  for(IOField<T, ?> field : fields){
					  args.getThis(field.getName());
				  }
			  });
			
			var clazzBytes = cw.getClassFile();
			ClassGenerationCommons.dumpClassName(proxyName, clazzBytes);
			if(ConfigDefs.CLASSGEN_PRINT_BYTECODE.resolveVal()){
				BytecodeUtils.printClass(clazzBytes);
			}
			
			try{
				//noinspection unchecked
				return (Class<ProxyBuilder<T>>)concreteClassAccess.defineClass(concreteClass, clazzBytes);
			}catch(IllegalAccessException|AccessProvider.Defunct e){
				throw new ShouldNeverHappenError(e);
			}
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate proxy for " + baseClass.getTypeName(), e);
		}
	}
	
	private static void writeField(ClassDefinition cw, FieldAccessor<?> field) throws MalformedJorth{
		FieldDefinition f = cw.field(field.getGenericType(null), field.getName());
		JorthUtils.writeAnnotations(f, field.getAnnotations().values());
	}
}
