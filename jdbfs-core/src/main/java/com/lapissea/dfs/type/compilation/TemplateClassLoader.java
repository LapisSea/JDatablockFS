package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOTypeDB;
import com.lapissea.dfs.type.InternalDataOrder;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.TypeDef;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.access.AnnotatedType;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOUnsafeValue;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.WeakValueHashMap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.lapissea.util.ConsoleColors.*;

public final class TemplateClassLoader extends ClassLoader{
	
	static{
		registerAsParallelCapable();
	}
	
	private record TypeNamed(String name, TypeDef def){ }
	
	private static final Map<TypeNamed, byte[]> CLASS_DATA_CACHE = Collections.synchronizedMap(new WeakValueHashMap<>());
	
	private static final boolean PRINT_GENERATING_INFO = ConfigDefs.CLASSGEN_PRINT_GENERATING_INFO.resolveVal();
	
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
						if(builtIn.map(stored -> !new TypeDef(c2).equals(stored)).orElse(false)){
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
		if(def.isUnmanaged()){
			throw new UnsupportedOperationException(className + " is unmanaged! All unmanaged types must be present! Unmanaged types may contain mechanism not understood by the base IO engine.");
		}
		
		if(!def.isIoInstance() && !def.isEnum() && !def.isJustInterface()){
			throw new UnsupportedOperationException("Can not generate: " + className + ". It is not an " + IOInstance.class.getSimpleName() + " or Enum or just an interface");
		}
		
		var classData = CLASS_DATA_CACHE.get(new TypeNamed(className, def));
		if(classData == null){
			var typ = new TypeNamed(className, def.clone());
			
			var hash = hashCode();
			Log.trace("Generating template: {} - {}", className, (Supplier<String>)() -> {
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
		if(PRINT_GENERATING_INFO) LogUtil.println("generating", "\n" + TextUtil.toTable(classType.name, classType.def.getFields()));
		
		var log = JorthLogger.make();
		try{
			var bytecode = Jorth.generateClass(this, classType.name, writer -> {
				for(var typeArg : classType.def.getTypeArgs()){
					var type = typeArg.bound().generic(db);
					writer.write("type-arg {!} {}", typeArg.name(), type);
				}
				
				if(classType.def.isIoInstance()){
					generateIOInstance(classType, writer);
				}else if(classType.def.isEnum()){
					generateEnum(classType, writer);
				}else if(classType.def.isJustInterface()){
					generateJustAnInterface(classType, writer);
				}else{
					throw new NotImplementedException(classType.name);
				}
			}, log == null? null : log::log);
			if(log != null) Log.log(log.output());
			ClassGenerationCommons.dumpClassName(classType.name, bytecode);
			
			return bytecode;
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class " + classType.name, e);
		}
	}
	
	private void generateEnum(TypeNamed classType, CodeStream writer) throws MalformedJorth{
		writer.write(
			"""
				public enum {!} start
				""",
			classType.name
		);
		for(var constant : classType.def.getEnumConstants()){
			writer.write("enum {!}", constant.getName());
		}
		writer.write("end");
	}
	
	private static void stringsAnnotation(CodeStream code, Class<? extends Annotation> type, Collection<String> values) throws MalformedJorth{
		code.write(
			"""
				@{0} start value [
					template-for #val in {1} start '#val' end
				] end
				""",
			type, values
		);
	}
	
	private void generateIOInstance(TypeNamed classType, CodeStream writer) throws MalformedJorth{
		writer.addImportAs(classType.name, "genClassName");
		
		writePermits(classType, writer);
		
		boolean extend = true;
		
		var parent = classType.def.getSealedParent();
		if(parent != null){
			ensureLoadedSealParent(parent.name());
			switch(parent.type()){
				case EXTEND -> {
					writer.write("extends {!}", parent.name());
					extend = false;
				}
				case JUST_INTERFACE -> writer.write("implements {!}", parent.name());
			}
		}
		
		var fields = classType.def.getFields();
		
		if(!fields.isEmpty()){
			var order = classType.def.getFieldOrder().mapToObj(fields::get).map(TypeDef.FieldDef::getName).collectToFinalList();
			//noinspection deprecation
			stringsAnnotation(writer, InternalDataOrder.class, order);
		}
		
		if(extend) writer.write("extends {}<#genClassName>", IOInstance.Managed.class);
		
		writer.addImport(Struct.class);
		writer.write("public class #genClassName start");
		if(extend && !classType.def.isSealed()){
			writer.write(
				"""
					private static final field $STRUCT #Struct
					
					function <clinit> start
						static call #Struct of start
							class #genClassName
						end
						set #genClassName $STRUCT
					end
					
					
					public function <init>
					start
						super start
							get #genClassName $STRUCT
						end
					end
					""");
		}
		
		for(var field : fields){
			writer.write("@{}", IOValue.class);
			
			if(IOFieldTools.canHaveNullAnnotation(new AnnotatedType.Simple(
				field.isDynamic()? List.of(Annotations.make(IOValue.Generic.class)) : List.of(),
				field.getType().getTypeClass(db)
			))){
				writer.write("@{} start value {!} end", IONullability.class, field.getNullability().toString());
			}
			if(field.isDynamic()){
				writer.write("@{}", IOValue.Generic.class);
			}
			if(field.isUnsigned()){
				writer.write("@{}", IOValue.Unsigned.class);
			}
			if(field.isUnsafe()){
				writer.write("@{}", IOUnsafeValue.class);
			}
			if(field.getReferenceType() != null){
				writer.write("@{} start dataPipeType {!} end", IOValue.Reference.class, field.getReferenceType().toString());
			}
			if(!field.getDependencies().isEmpty()){
				stringsAnnotation(writer, IODependency.class, field.getDependencies());
			}
			
			writer.write("private field {!} {}", field.getName(), field.getType().generic(db));
		}
		writer.write("end");
	}
	
	private void generateJustAnInterface(TypeNamed classType, CodeStream writer) throws MalformedJorth{
		writePermits(classType, writer);
		
		var parent = classType.def.getSealedParent();
		if(parent != null){
			ensureLoadedSealParent(parent.name());
			switch(parent.type()){
				case EXTEND -> {
					throw new IllegalStateException("Interface can not have an extends");
				}
				case JUST_INTERFACE -> writer.write("implements {!}", parent.name());
			}
		}
		writer.write("public interface {!} start end", classType.name);
	}
	
	private void ensureLoadedSealParent(String pName){
		try{
			getDef(pName);
		}catch(ClassNotFoundException e){
			throw new IllegalStateException("Sealed parent must be registered before a child", e);
		}
	}
	
	private static void writePermits(TypeNamed classType, CodeStream writer) throws MalformedJorth{
		if(classType.def.isSealed()){
			for(var subclass : classType.def.getPermittedSubclasses()){
				writer.write("permits {!}", subclass);
			}
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
