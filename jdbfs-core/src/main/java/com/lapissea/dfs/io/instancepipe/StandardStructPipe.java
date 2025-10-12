package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.internal.AccessProvider;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.content.BBView;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.compilation.JorthLogger;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOField.SpecializedGenerator.AccessMap;
import com.lapissea.dfs.type.field.IOField.SpecializedGenerator.AccessMap.ConstantRequest;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.VirtualAccessor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.jorth.BytecodeUtils;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.lapissea.dfs.type.field.StoragePool.IO;

public class StandardStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public static <T extends IOInstance<T>> long sizeOfUnknown(DataProvider provider, T instance, WordSpace wordSpace){
		var pip = StandardStructPipe.of(instance.getThisStruct(), StructPipe.STATE_DONE);
		return pip.calcUnknownSize(provider, instance, wordSpace);
	}
	
	public static <T extends IOInstance<T>> StandardStructPipe<T> of(Class<T> type){
		return of(Struct.of(type));
	}
	public static <T extends IOInstance<T>> StandardStructPipe<T> of(Class<T> type, int minRequestedStage){
		Struct<T> struct;
		if(minRequestedStage == STATE_DONE){
			struct = Struct.of(type, STATE_DONE);
		}else{
			struct = Struct.of(type);
		}
		return of(struct, minRequestedStage);
	}
	public static <T extends IOInstance<T>> StandardStructPipe<T> of(Struct<T> struct){
		return of(StandardStructPipe.class, struct);
	}
	public static <T extends IOInstance<T>> StandardStructPipe<T> of(Struct<T> struct, int minRequestedStage){
		return of(StandardStructPipe.class, struct, minRequestedStage);
	}
	
	protected StandardStructPipe(Struct<T> type, PipeFieldCompiler<T, RuntimeException> compiler, int syncStage){
		super(type, compiler, syncStage);
	}
	public StandardStructPipe(Struct<T> type, int syncStage){
		super(type, (t, structFields, testRun) -> {
			var fields = IOFieldTools.stepFinal(structFields, List.of(
				IOFieldTools::dependencyReorder,
				IOFieldTools::mergeBitSpace
			));
			return new PipeFieldCompiler.Result<>(fields);
		}, syncStage);
	}
	
	@Override
	public Class<StructPipe<T>> getSelfClass(){
		//noinspection unchecked,rawtypes
		return (Class)StandardStructPipe.class;
	}
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		writeIOFields(getSpecificFields(), ioPool, provider, dest, instance);
	}
	
	@Override
	protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
//		if(GlobalConfig.DEBUG_VALIDATION){
//			skipCheck(ioPool, provider, instance, src, genericContext);
//		}
		
		return genericDoRead(ioPool, provider, src, instance, genericContext);
	}
	private T genericDoRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		FieldSet<T> fields = getSpecificFields();
		for(IOField<T, ?> field : fields){
			readField(ioPool, provider, src, instance, genericContext, field);
		}
		return instance;
	}
	
	private void skipCheck(VarPool<T> ioPool, DataProvider provider, T instance, ContentReader src, GenericContext genericContext) throws IOException{
		if(!(src instanceof RandomIO rand) || instance instanceof IOInstance.Unmanaged) return;
		
		long skipped;
		var  p = rand.getPos();
		try{
			skip(provider, src, genericContext);
			skipped = rand.getPos() - p;
		}finally{
			rand.setPos(p);
		}
		
		FieldSet<T> fields = getSpecificFields();
		for(IOField<T, ?> field : fields){
			readField(ioPool, provider, src, instance, genericContext, field);
		}
		var read = rand.getPos() - p;
		rand.setPos(p);
		
		if(skipped != read){
			throw new IllegalStateException("Skipped " + skipped + " bytes but read " + read);
		}
	}
	
	private static final int SKIP_FIXED = 0;
	private static final int SKIP       = 1;
	private static final int READ       = 2;
	
	private record SkipData<T extends IOInstance<T>>(FieldSet<T> fields, byte[] cmds, boolean needsInstance, boolean needsPool){ }
	
	private SkipData<T> skipCache;
	
	private SkipData<T> skipData(){
		var s = skipCache;
		if(s == null) skipCache = s = calcSkip();
		return s;
	}
	
	private sealed interface CmdBuild{
		int siz();
		void write(int off, byte[] dest);
		
		record Skip(boolean needsIOPool) implements CmdBuild{
			@Override
			public int siz(){ return 1; }
			@Override
			public void write(int off, byte[] dest){
				dest[off] = SKIP;
			}
		}
		
		record SkipFixed(long bytes, int extraFieldSkips) implements CmdBuild{
			@Override
			public int siz(){ return 1 + 8 + 4; }
			@Override
			public void write(int off, byte[] dest){
				dest[off] = SKIP_FIXED;
				BBView.writeInt8(dest, off + 1, bytes);
				BBView.writeInt4(dest, off + 1 + 8, extraFieldSkips);
			}
		}
		
		record Read(boolean needsInstance, boolean needsIOPool) implements CmdBuild{
			@Override
			public int siz(){ return 1; }
			@Override
			public void write(int off, byte[] dest){
				dest[off] = READ;
			}
		}
	}
	
	private SkipData<T> calcSkip(){
		var report = createSizeReport(0);
		if(report.dynamic()) return null;
		
		FieldSet<T>    fields = report.allFields();
		List<CmdBuild> cmds   = new ArrayList<>(fields.size());
		
		for(IOField<T, ?> field : fields){
			
			//If any other field depends on this field, then it has to be read
			if(field.iterUnpackedFields().flatMap(fields::iterDependentOn).hasAny()){
				var needsInstance = !field.iterUnpackedFields().allMatch(f -> f.isVirtual(IO));
				cmds.add(new CmdBuild.Read(needsInstance, field.needsIOPool()));
				continue;
			}
			
			var fixedSizeO = field.getSizeDescriptor().getFixed(WordSpace.BYTE);
			if(fixedSizeO.isPresent()){
				var fixedSize = fixedSizeO.getAsLong();
				if(!cmds.isEmpty() && cmds.getLast() instanceof CmdBuild.SkipFixed(long bytes, int extraFieldSkips)){
					cmds.set(cmds.size() - 1, new CmdBuild.SkipFixed(bytes + fixedSize, extraFieldSkips + 1));
				}else{
					cmds.add(new CmdBuild.SkipFixed(fixedSize, 0));
				}
				continue;
			}
			
			cmds.add(new CmdBuild.Skip(field.needsIOPool()));
		}
		
		var buff = new byte[Iters.from(cmds).mapToInt(CmdBuild::siz).sum()];
		var pos  = 0;
		for(var c : cmds){
			c.write(pos, buff);
			pos += c.siz();
		}
		
		assert pos == buff.length;
		
		boolean needsInstance = Iters.from(cmds).anyMatch(c -> switch(c){
			case CmdBuild.Skip ignored -> false;
			case CmdBuild.SkipFixed ignored -> false;
			case CmdBuild.Read read -> read.needsInstance;
		});
		boolean needsIOPool = Iters.from(cmds).anyMatch(c -> switch(c){
			case CmdBuild.Skip skip -> skip.needsIOPool;
			case CmdBuild.SkipFixed ignored -> false;
			case CmdBuild.Read read -> read.needsIOPool;
		});
		
		return new SkipData<>(fields, buff, needsInstance, needsIOPool);
	}
	
	@Override
	public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var skip = skipData();
		if(skip == null){
			readNew(provider, src, genericContext);
			return;
		}
		if(skip.needsInstance && needsBuilderObj()){
			getBuilderPipe().skip(provider, src, genericContext);
			return;
		}
		
		var pool = skip.needsPool? makeIOPool() : null;
		var inst = skip.needsInstance? getType().make() : null;
		
		var cmds = skip.cmds;
		for(int f = 0, c = 0; c<cmds.length; f++){
			switch(cmds[c++]){
				case SKIP_FIXED -> {
					src.skipExact(BBView.readInt8(cmds, c));
					c += 8;
					f += BBView.readInt4(cmds, c);
					c += 4;
				}
				case READ -> skip.fields.get(f).read(pool, provider, src, inst, genericContext);
				case SKIP -> skip.fields.get(f).skip(pool, provider, src, inst, genericContext);
				default -> throw new IllegalStateException();
			}
		}
	}
	
	private static MethodHandle genericDoRead;
	private static MethodHandle genericReadNew;
	private static synchronized MethodHandle getGenericDoRead() throws IllegalAccessException, NoSuchMethodException{
		if(genericDoRead == null){
			var mt = StandardStructPipe.class.getDeclaredMethod(
				"genericDoRead",
				VarPool.class, DataProvider.class, ContentReader.class, IOInstance.class, GenericContext.class);
			genericDoRead = MethodHandles.lookup().unreflect(mt);
		}
		return genericDoRead;
	}
	private static synchronized MethodHandle getGenericReadNew() throws IllegalAccessException, NoSuchMethodException{
		if(genericReadNew == null){
			var mt = StandardStructPipe.class.getDeclaredMethod(
				"genericReadNew", DataProvider.class, ContentReader.class, GenericContext.class);
			genericReadNew = MethodHandles.lookup().unreflect(mt);
		}
		return genericReadNew;
	}
	
	private T genericReadNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		return super.readNew(provider, src, genericContext);
	}
	
	public static <T extends IOInstance<T>> CallSite bootstrapDoRead(MethodHandles.Lookup lookup, String name, MethodType ignore, Class<T> objType) throws Throwable{
		Log.debug("Generating specialized {}#yellow for {}#green", name, objType.getName());
		MethodHandle         target;
		Set<ConstantRequest> constants = new HashSet<>();
		while(true){
			try{
				List<IOField.SpecializedGenerator> generators = getSpecializedGenerators(objType);
				
				target = makeImpl(lookup, name, writer -> {
					writer.addImportAs(objType, "ObjType");
					writer.addImports(
						VarPool.class, DataProvider.class, ContentReader.class,
						GenericContext.class, StandardStructPipe.class, IOInstance.class,
						Struct.class
					);
					
					var accessMap = new AccessMap();
					accessMap.setup(true);
					
					writeConstants(writer, constants, accessMap);
					
					generateFunction_doRead(name, writer, generators, accessMap);
				});
			}catch(AccessMap.ConstantNeeded e){
				constants.add(e.constant);
				continue;
			}catch(Throwable t){
				new RuntimeException("Failed to generate specialized implementation for " + objType.getTypeName(), t).printStackTrace();
				if(!ConfigDefs.CLASSGEN_SPECIALIZATION_FALLBACK.resolveVal()) throw t;
				target = getGenericDoRead();
			}
			return new ConstantCallSite(target);
		}
	}
	
	private static void generateFunction_doRead(String name, CodeStream writer, List<IOField.SpecializedGenerator> generators, AccessMap accessMap) throws MalformedJorth, AccessMap.ConstantNeeded{
		writer.write(
			"""
				public static function {0}
					arg ioPool #VarPool<#ObjType>
					arg provider #DataProvider
					arg src #ContentReader
					arg instance #ObjType
					arg genericContext #GenericContext
					returns #ObjType
				start
					get #arg instance
				""",
			name
		);
		
		for(IOField.SpecializedGenerator generator : generators){
			generator.injectReadField(writer, accessMap);
		}
		
		writer.write(
			"""
					return
				end
				"""
		);
	}
	
	public static <T extends IOInstance<T>> CallSite bootstrapReadNew(MethodHandles.Lookup lookup, String name, MethodType ignore, Class<T> objType) throws Throwable{
		Log.debug("Generating specialized {}#yellow for {}#green", name, objType.getName());
		MethodHandle target;
		
		Set<ConstantRequest>               constants  = new HashSet<>();
		List<IOField.SpecializedGenerator> generators = getSpecializedGenerators(objType);
		
		while(true){
			try{
				target = makeImpl(lookup, name, writer -> {
					writer.addImportAs(objType, "ObjType");
					writer.addImports(
						VarPool.class, DataProvider.class, ContentReader.class,
						GenericContext.class, StandardStructPipe.class, IOInstance.class
					);
					var accessMap = new AccessMap();
					writeConstants(writer, constants, accessMap);
					generateFunction_readNew(name, writer, generators, accessMap);
				});
			}catch(AccessMap.ConstantNeeded e){
				constants.add(e.constant);
				continue;
			}catch(Throwable t){
				new RuntimeException("Failed to generate specialized implementation for " + objType.getTypeName(), t).printStackTrace();
				if(!ConfigDefs.CLASSGEN_SPECIALIZATION_FALLBACK.resolveVal()) throw t;
				target = getGenericReadNew();
			}
			return new ConstantCallSite(target);
		}
	}
	
	private static void generateFunction_readNew(String functionName, CodeStream writer, List<IOField.SpecializedGenerator> generators, AccessMap accessMap) throws MalformedJorth, AccessMap.ConstantNeeded{
		writer.write(
			"""
				public static function {0}
					arg provider #DataProvider
					arg src #ContentReader
					arg genericContext #GenericContext
					returns #ObjType
				start
					new #ObjType
				""",
			functionName
		);
		
		for(IOField.SpecializedGenerator generator : generators){
			generator.injectReadField(writer, accessMap);
		}
		
		writer.write(
			"""
					return
				end
				"""
		);
	}
	
	private static void writeConstants(CodeStream writer, Set<ConstantRequest> constants, AccessMap accessMap) throws MalformedJorth{
		record Acc(FieldAccessor<?> accessor, String name){ }
		record EArr(Class<?> type, String name){ }
		List<Acc>  accessors = new ArrayList<>();
		List<EArr> enumArrs  = new ArrayList<>();
		
		int i = -1;
		for(ConstantRequest constant : constants){
			i++;
			switch(constant){
				case ConstantRequest.EnumArr(var type) -> {
					var name = "eArr_" + i + "_" + type.getSimpleName().replaceAll("[^A-Za-z]", "");
					enumArrs.add(new EArr(type, name));
					writer.write("private static final field {} {}", name, type.arrayType());
					accessMap.addEnumArray(type, "#ThisClass", name);
				}
				case ConstantRequest.FieldAcc(var accessor) -> {
					var name = "acc_" + i + "_" + accessor.getName().replaceAll("[^A-Za-z]", "");
					accessors.add(new Acc(accessor, name));
					writer.write("private static final field {} {}<#ObjType>", name, VirtualAccessor.class);
					accessMap.addAccessorField(accessor, "#ThisClass", name);
				}
			}
		}
		
		writer.write("public static function <clinit> start");
		
		if(!accessors.isEmpty()){
			writer.write(
				"""
						static call #Struct of start
							class #ObjType
							{}
						end
						call getFields
					""", Struct.STATE_FIELD_MAKE);
			for(var it = accessors.iterator(); it.hasNext(); ){
				var acc = it.next();
				if(it.hasNext()){
					writer.write("dup");
				}
				writer.write("call requireByName start '{}' end", acc.accessor.getName());
				writer.write("call getAccessor cast {}", VirtualAccessor.class);
				writer.write("set #ThisClass {}", acc.name);
			}
		}
		
		
		for(EArr enumArr : enumArrs){
			writer.write(
				"""
					static call {} values
					set #ThisClass {}
					""",
				enumArr.type, enumArr.name
			);
		}
		
		writer.wEnd();
	}
	
	private static MethodHandle makeImpl(MethodHandles.Lookup lookup, String fnName, UnsafeConsumer<CodeStream, Throwable> generateFn) throws Throwable{
		var c     = lookup.lookupClass();
		var cname = c.getName();
		
		cname = (c.isHidden()? cname.substring(0, cname.lastIndexOf('/')) : cname) + "&_" + fnName;
		
		var log   = JorthLogger.make();
		var jorth = new Jorth(lookup.lookupClass().getClassLoader(), log);
		jorth.addImportAs(cname, "ThisClass");
		try{
			try(var writer = jorth.writer()){
				writer.write("class #ThisClass start");
				generateFn.accept(writer);
				writer.wEnd();
			}
			
			var bb        = jorth.getClassFile(cname);
			var implClass = lookup.defineHiddenClass(bb, true);
			var method = Iters.from(implClass.lookupClass().getMethods())
			                  .filter(e -> e.getName().equals(fnName))
			                  .getFirst();
			
			return implClass.unreflect(method);
		}finally{
			if(log != null){
				Log.log("Generated jorth for bootstrap implementation:\n" + log.output());
			}
		}
	}
	
	private static <T extends IOInstance<T>> List<IOField.SpecializedGenerator> getSpecializedGenerators(Class<T> objType){
		var struct = Struct.of(objType, Struct.STATE_FIELD_MAKE);
		var pipe   = new StandardStructPipe<>(struct, StructPipe.STATE_IO_FIELD);
		
		var                                fields     = pipe.getSpecificFields();
		List<IOField.SpecializedGenerator> generators = new ArrayList<>(fields.size());
		
		for(IOField<?, ?> field : fields){
			if(field instanceof IOField.SpecializedGenerator sg){
				generators.add(sg);
				continue;
			}
			throw new UnsupportedOperationException(
				Log.fmt("""
					        Not all fields support code generation:
					          Type:  {}#red
					          Field: {}#red - {}#red
					        """, objType.getTypeName(), field, field.getClass().getTypeName()));
		}
		return generators;
	}
	
	@Override
	protected Match<StructPipe<T>> buildSpecializedImplementation(int syncStage){
		var type = getType().getType();
		
		List<IOField.SpecializedGenerator> generators;
		if(getType().getInitializationState()>=StructPipe.STATE_IO_FIELD){
			List<IOField.SpecializedGenerator> generatorsTmp = null;
			try{
				generatorsTmp = getSpecializedGenerators(type);
			}catch(UnsupportedOperationException t){
				new RuntimeException("Failed to get specialized generators", t).printStackTrace();
				if(!ConfigDefs.CLASSGEN_SPECIALIZATION_FALLBACK.resolveVal()) throw t;
			}
			generators = generatorsTmp;
		}else generators = null;
		
		Set<ConstantRequest> constants = new HashSet<>();
		
		while(true){
			var log = JorthLogger.make();
			try{
				var className = type.getName() + "&GeneratedPipe_" + type.getSimpleName();
				
				var jorth = new Jorth(type.getClassLoader(), log);
				try(var writer = jorth.writer()){
					
					writer.addImportAs(type, "ObjType");
					writer.addImportAs(className, "ThisClass");
					writer.addImports(
						Struct.class,
						VarPool.class, DataProvider.class, ContentReader.class,
						GenericContext.class, StandardStructPipe.class, IOInstance.class
					);
					
					writer.write(
						"""
							extends #StandardStructPipe<#ObjType>
							implements {}
							public class #ThisClass start
							""",
						SpecializedImplementation.class
					);
					writer.write(
						"""
							public function <init> start
								super start
									static call #Struct of start
										class {!}
									end
									{!}
								end
							end
							""",
						type.getName(),
						StructPipe.STATE_DONE
					);
					
					if(generators != null){
						var accessMap = new AccessMap();
						writeConstants(writer, constants, accessMap);
						
						accessMap.setup(false);
						generateFunction_readNew("specialized_readNew", writer, generators, accessMap);
						
						accessMap.setup(true);
						generateFunction_doRead("specialized_doRead", writer, generators, accessMap);
					}
					
					writer.write(
						"""
							@ #Override
							protected function doRead
								arg ioPool #VarPool<#ObjType>
								arg provider #DataProvider
								arg src #ContentReader
								arg instance #IOInstance
								arg genericContext #GenericContext
								returns #IOInstance
							start
							"""
					);
					if(generators != null){
						directCall_doRead(writer);
					}else{
						virtualCall_doRead(writer);
					}
					writer.write(
						"""
								return
							end
							
							@ #Override
							protected function readNew
								arg provider #DataProvider
								arg src #ContentReader
								arg genericContext #GenericContext
								returns #IOInstance
							start
							"""
					);
					if(generators != null){
						directCall_readNew(writer);
					}else{
						virtualCall_readNew(writer);
					}
					writer.write(
						"""
								return
							end
							"""
					);
					
					writer.wEnd();
				}
				
				var bytecode = jorth.getClassFile(className);
				BytecodeUtils.printClass(bytecode);
				
				var access = Access.findAccess(type, Access.Mode.PRIVATE, Access.Mode.MODULE);
				var cls    = access.defineClass(type, bytecode, true);
				//noinspection unchecked
				return Match.of((StructPipe<T>)cls.getConstructor().newInstance());
			}catch(AccessMap.ConstantNeeded e){
				var added = constants.add(e.constant);
				if(!added){
					throw new IllegalStateException("Accessor already added");
				}
				log = null;
			}catch(MalformedJorth e){
				throw new RuntimeException("Failed to generate specialized pipe for type: " + type.getTypeName(), e);
			}catch(AccessProvider.Defunct e){
				throw new ShouldNeverHappenError(e);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException("Failed to instantiate specialized pipe for type: " + type.getTypeName(), e);
			}finally{
				if(log != null){
					Log.log("Generated jorth for buildSpecializedImplementation:\n" + log.output());
				}
			}
		}
	}
	
	private static void directCall_readNew(CodeStream writer) throws MalformedJorth{
		writer.write(
			"""
				static call #ThisClass specialized_readNew start
					get #arg provider
					get #arg src
					get #arg genericContext
				end
				"""
		);
	}
	private static void directCall_doRead(CodeStream writer) throws MalformedJorth{
		writer.write(
			"""
				static call #ThisClass specialized_doRead start
					get #arg ioPool
					get #arg provider
					get #arg src
					get #arg instance cast #ObjType
					get #arg genericContext
				end
				"""
		);
	}
	
	private static void virtualCall_readNew(CodeStream writer) throws MalformedJorth{
		writer.write(
			"""
					call-virtual
						bootstrap-fn #StandardStructPipe bootstrapReadNew
							arg #Class #ObjType
						calling-fn readNew start
							arg #DataProvider
							arg #ContentReader
							arg #GenericContext
							returns #ObjType
						end
					start
						get #arg provider
						get #arg src
						get #arg genericContext
					end
				
				"""
		);
	}
	private static void virtualCall_doRead(CodeStream writer) throws MalformedJorth{
		writer.write(
			"""
					call-virtual
						bootstrap-fn #StandardStructPipe bootstrapDoRead
							arg #Class #ObjType
						calling-fn doRead start
							arg #VarPool<#ObjType>
							arg #DataProvider
							arg #ContentReader
							arg #ObjType
							arg #GenericContext
							returns #ObjType
						end
					start
						get #arg ioPool
						get #arg provider
						get #arg src
						get #arg instance cast #ObjType
						get #arg genericContext
					end
				
				"""
		);
	}
	
}
