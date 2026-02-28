package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.core.DataProvider;
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
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SpecializedGenerator;
import com.lapissea.dfs.type.field.SpecializedGenerator.AccessMap.ConstantRequest;
import com.lapissea.dfs.type.field.StoragePool;
import com.lapissea.iterableplus.Iters;
import com.lapissea.iterableplus.Match;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

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
	
	private static <T extends IOInstance<T>> PipeFieldCompiler.Result<T> standardCompile(Struct<T> t, FieldSet<T> structFields, boolean testRun){
		var fields = IOFieldTools.stepFinal(structFields, List.of(
			IOFieldTools::dependencyReorder,
			IOFieldTools::mergeBitSpace
		));
		return new PipeFieldCompiler.Result<>(fields);
	}
	
	protected StandardStructPipe(Struct<T> type, PipeFieldCompiler<T, RuntimeException> compiler, int syncStage){
		super(type, compiler, syncStage);
	}
	public StandardStructPipe(Struct<T> type, int syncStage){
		super(type, StandardStructPipe::standardCompile, syncStage);
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
				var needsInstance = !field.iterUnpackedFields().filter(e -> e.getAccessor() != null).allMatch(f -> f.isVirtual(StoragePool.IO));
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
	
	private T genericReadNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		return super.readNew(provider, src, genericContext);
	}
	
	public static <T extends IOInstance<T>> CallSite bootstrapDoRead(MethodHandles.Lookup lookup, String name, MethodType ignore, Class<T> objType) throws Throwable{
		var fields = makeSTDFields(objType);
		return PipeCodeGen.boostrapDoReadFromFields(lookup, name, objType, fields);
	}
	
	public static <T extends IOInstance<T>> CallSite bootstrapReadNew(MethodHandles.Lookup lookup, String name, MethodType ignore, Class<T> objType) throws Throwable{
		var fields = makeSTDFields(objType);
		return PipeCodeGen.boostrapReadNewFromFields(lookup, name, objType, fields);
	}
	
	@Override
	protected Match<PipeCodeGen.PipeWriter<T>> getSpecializedImplementationWriter(){
		return Match.of((writer, constants, type) -> {
			PipeCodeGen.defaultClassDef(writer);
			
			boolean hasReadyStruct = getType().getInitializationState()>=StructPipe.STATE_IO_FIELD;
			constants.add(new ConstantRequest.DebugField(boolean.class, "DEBUG_READY_READ", hasReadyStruct + ""));
			
			List<SpecializedGenerator> generators;
			if(hasReadyStruct){
				for(int i = 0; i<10; i++){
					if(getInitializationState()>=STATE_IO_FIELD) break;
					UtilL.sleep(2);
				}
				if(getInitializationState()>=STATE_IO_FIELD){
					var fields = getSpecificFields();
					generators = PipeCodeGen.getSpecializedGenerators(type, fields);
				}else{
					Log.info("Delaying codegen to boostrap because {}#yellow is not ready", this);
					generators = null;
				}
			}else generators = null;
			
			PipeCodeGen.standardPipeImpl(writer, constants, type, getType(), generators);
			
			writer.wEnd();
		});
	}
	private static <T extends IOInstance<T>> List<IOField<T, ?>> makeSTDFields(Class<T> type){
		var struct = Struct.of(type, Struct.STATE_FIELD_MAKE);
		return standardCompile(struct, struct.getFields(), false).fields();
	}
	
}
