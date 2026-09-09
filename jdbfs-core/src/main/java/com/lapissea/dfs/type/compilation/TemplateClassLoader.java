package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOTypeDB;
import com.lapissea.dfs.type.InternalDataOrder;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.def.FieldDef;
import com.lapissea.dfs.type.def.TypeDef;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOUnsafeValue;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.string.StringifySettings;
import com.lapissea.iterableplus.Iters;
import com.lapissea.jorth.AnnotationContainer;
import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.WeakValueHashMap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static com.lapissea.util.ConsoleColors.*;

public final class TemplateClassLoader extends ClassLoader{
	
	static{
		registerAsParallelCapable();
	}
	
	private record TypeNamed(String name, TypeDef def){ }
	
	private static final Map<TypeNamed, byte[]> CLASS_DATA_CACHE = Collections.synchronizedMap(new WeakValueHashMap<>());
	
	private static final boolean PRINT_GENERATING_INFO = ConfigDefs.CLASSGEN_PRINT_GENERATING_INFO.resolveValLocking();
	
	private final IOTypeDB db;
	
	public TemplateClassLoader(IOTypeDB db, ClassLoader parent){
		super(TemplateClassLoader.class.getSimpleName() + "{" + db + "}", parent);
		this.db = db;
	}
	
	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
		synchronized(getClassLoadingLock(name)){
			// First, check if the class has already been loaded
			Class<?> c1 = findLoadedClass(name);
			if(c1 == null){
				var parent = getParent();
				try{
					var c2 = c1 = parent.loadClass(name);
					
					if(!name.startsWith("java.")) try{
						var builtIn = db.getDefinitionFromClassName(name);
						if(builtIn.map(stored -> !TypeDef.of(c2).equals(stored)).orElse(false)){
							c1 = null;//Discard mismatching built in class
						}
					}catch(IOException e){
						throw new UncheckedIOException("Failed to fetch data from database", e);
					}
				}catch(ClassNotFoundException ignored){ }
				
				if(c1 == null){
					// If still not found, then invoke findClass in order
					// to find the class.
					c1 = findClass(name);
				}
			}
			if(resolve){
				resolveClass(c1);
			}
			return c1;
		}
	}
	
	@Override
	protected Class<?> findClass(String className) throws ClassNotFoundException{
		TypeDef def = getDef(className);
		
		switch(def){
			case TypeDef.DEnum ignore -> { }
			case TypeDef.DInstance ignore -> { }
			case TypeDef.DJustInterface ignore -> { }
			case TypeDef.DUnknown ignore -> {
				throw new UnsupportedOperationException(
					"Can not generate: " + className + ". It is not an " + IOInstance.class.getSimpleName() +
					" or Enum or just an interface"
				);
			}
			case TypeDef.DUnmanaged ignore -> {
				throw new UnsupportedOperationException(
					className + " is unmanaged! All unmanaged types must be present! " +
					"Unmanaged types may contain mechanism not understood by the base IO engine."
				);
			}
		}
		
		var typ       = new TypeNamed(className, def);
		var classData = CLASS_DATA_CACHE.get(typ);
		if(classData == null){
			var hash = hashCode();
			ConfigDefs.CompLogLevel.JUST_START.log("Generating template: {} - {}", className, (Supplier<String>)() -> {
				var cols = List.of(BLACK, RED, GREEN, YELLOW, BLUE, PURPLE, CYAN);
				return cols.get((int)(Integer.toUnsignedLong(hash)%cols.size())) + Integer.toHexString(hash) + " " + RESET;
			});
			
			try{
				classData = jorthGenerate(typ);
			}catch(Throwable e){
				throw handleClassgenFail(e);
			}
			CLASS_DATA_CACHE.put(typ, classData);
		}
		
		try{
			return defineClass(className, ByteBuffer.wrap(classData), null);
		}catch(Throwable e){
			throw handleClassgenFail(e);
		}
	}
	private static RuntimeException handleClassgenFail(Throwable e){
		e.printStackTrace();
		if(ConfigDefs.CLASSGEN_EXIT_ON_FAIL.resolveVal()){
			e.printStackTrace();
			throw UtilL.sysExit(1);
		}
		throw UtilL.uncheckedThrow(e);
	}
	
	private byte[] jorthGenerate(TypeNamed classType){
		if(PRINT_GENERATING_INFO) logGenerateInfo(classType);
		
		try{
			var cw = new ClassDefinition(this);
			for(var typeArg : classType.def.getRelations().typeArgs){
				var type = typeArg.bound().generic(db);
				cw.genericArg(type, typeArg.name());
			}
			
			switch(classType.def){
				case TypeDef.DEnum def -> generateEnum(classType.name, def, cw);
				case TypeDef.DInstance def -> generateIOInstance(classType.name, def, cw);
				case TypeDef.DJustInterface def -> generateJustAnInterface(classType.name, def, cw);
				case TypeDef.DUnknown ignore -> throw new UnsupportedOperationException("Can not generate unkown type");
				case TypeDef.DUnmanaged ignore -> throw new UnsupportedOperationException("Can not generate unmanaged type");
			}
			
			var bytecode = cw.getClassFile();
			ClassGenerationCommons.dumpClassName(classType.name, bytecode);
			
			return bytecode;
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class " + classType.name, e);
		}
	}
	
	private static void logGenerateInfo(TypeNamed classType){
		Struct typ = Struct.ofUnknown(classType.def.getClass());
		var    str = typ.instanceToString((IOInstance<?>)classType.def, StringifySettings.DEFAULT);
		LogUtil.println("generating", "\n" + classType.name + ": " + str);
	}
	
	private void generateEnum(String name, TypeDef.DEnum def, ClassDefinition cw) throws MalformedJorth{
		cw.name(ClassName.dotted(name)).type(ClassType.ENUM);
		for(String enumConstant : def.enumConstants){
			cw.enumConstant(enumConstant);
		}
	}
	
	private static void stringsAnnotation(AnnotationContainer<?> target, Class<? extends Annotation> type, Collection<String> values) throws MalformedJorth{
		target.annotation(type, Map.of("value", values.toArray(String[]::new)));
	}
	
	private void generateIOInstance(String name, TypeDef.DInstance def, ClassDefinition cw) throws MalformedJorth{
		var genClassName = ClassName.dotted(name);
		cw.typeDef("genClassName", genClassName);
		
		writePermits(def, cw);
		
		boolean extend = true;
		
		var parent = def.getRelations().sealedParent;
		if(parent != null){
			ensureLoadedSealParent(parent.name());
			switch(parent.type()){
				case EXTEND -> {
					cw.extendsType(ClassName.dotted(parent.name()));
					extend = false;
				}
				case JUST_INTERFACE -> cw.implement(ClassName.dotted(parent.name()));
			}
		}
		
		var fields = def.fields;
		
		if(!fields.isEmpty()){
			var order = Iters.from(def.fieldOrder).map(fields::get).toList(FieldDef::getName);
			//noinspection deprecation
			stringsAnnotation(cw, InternalDataOrder.class, order);
		}
		
		if(extend) cw.extendsType(GenericType.of(IOInstance.Managed.class).withArgs(genClassName));
		
		cw.name(genClassName);
		if(!def.isSealed()) cw.finalAcc();
		if(extend && !def.isSealed()){
			var structType = GenericType.of(Struct.class).withArgs(genClassName);
			var vStruct    = cw.field(structType, "$V_STRUCT").visibility(Visibility.PRIVATE).staticAcc();
			
			cw.function("$STRUCT").visibility(Visibility.PRIVATE).staticAcc()
			  .returns(structType)
			  .body()
			  .call(Objects.class, "isNull", e -> e.get(vStruct))
			  .ifTrue(e -> {
				  e.call(Struct.class, "of", args -> args.val(genClassName))
				   .setField(vStruct);
			  })
			  .get(vStruct);
			
			cw.staticInit()
			  .body()
			  .call(IOInstance.Managed.class, "allowFullAccess", e -> e.call(MethodHandles.class, "lookup"));
			
			cw.instanceInit()
			  .body()
			  .callSuper(args -> args.call(genClassName, "$STRUCT"));
			
		}else{
			cw.staticInit()
			  .body()
			  .call(IOInstance.Managed.class, "allowFullAccess", e -> e.call(MethodHandles.class, "lookup"));
		}
		
		for(var field : fields){
			var f = cw.field(field.type.generic(db), field.name)
			          .visibility(Visibility.PRIVATE)
			          .annotation(IOValue.class);
			
			for(var annO : field.annotations){
				switch(annO){
					case FieldDef.IOAnnotation.AnDependencies ann -> {
						stringsAnnotation(f, IODependency.class, ann.names());
					}
					case FieldDef.IOAnnotation.AnNumberSize ann -> {
						f.annotation(IODependency.NumSize.class, Map.of("value", ann.fieldName));
					}
					case FieldDef.IOAnnotation.AnGeneric ignore -> {
						f.annotation(IOValue.Generic.class);
					}
					case FieldDef.IOAnnotation.AnNullability ann -> {
						f.annotation(IONullability.class, Map.of("value", ann.mode));
					}
					case FieldDef.IOAnnotation.AnReferenceType ann -> {
						f.annotation(IOValue.Reference.class, Map.of("dataPipeType", ann.type));
					}
					case FieldDef.IOAnnotation.AnUnsafe ignore -> {
						f.annotation(IOUnsafeValue.class);
					}
					case FieldDef.IOAnnotation.AnUnsigned ignore -> {
						f.annotation(IOValue.Unsigned.class);
					}
				}
			}
		}
	}
	
	private void generateJustAnInterface(String name, TypeDef.DJustInterface def, ClassDefinition cw) throws MalformedJorth{
		writePermits(def, cw);
		
		var parent = def.relations.sealedParent;
		if(parent != null){
			ensureLoadedSealParent(parent.name());
			switch(parent.type()){
				case EXTEND -> {
					throw new IllegalStateException("Interface can not have an extends");
				}
				case JUST_INTERFACE -> cw.implement(ClassName.dotted(parent.name()));
			}
		}
		cw.type(ClassType.INTERFACE).name(ClassName.dotted(name));
	}
	
	private void ensureLoadedSealParent(String pName){
		try{
			getDef(pName);
		}catch(ClassNotFoundException e){
			throw new IllegalStateException("Sealed parent must be registered before a child", e);
		}
	}
	
	private static void writePermits(TypeDef def, ClassDefinition cw) throws MalformedJorth{
		for(var subclass : def.getRelations().permittedSubclasses){
			cw.permits(ClassName.dotted(subclass));
		}
	}
	
	private TypeDef getDef(String name) throws ClassNotFoundException{
		try{
			return db.getDefinitionFromClassName(name).orElseThrow(() -> {
				return new ClassNotFoundException(name + " is not defined in database");
			});
		}catch(IOException e){
			throw new RuntimeException("Failed to fetch data from database", e);
		}
	}
}
