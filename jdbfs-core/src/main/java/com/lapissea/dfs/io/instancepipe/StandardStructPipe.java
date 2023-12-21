package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.content.BBView;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.lapissea.dfs.type.field.StoragePool.IO;

public class StandardStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public static <T extends IOInstance<T>> PipeFieldCompiler<T, RuntimeException> compiler(){
		return (t, structFields) -> IOFieldTools.stepFinal(structFields, List.of(
			IOFieldTools::dependencyReorder,
			IOFieldTools::mergeBitSpace
		));
	}
	
	public static <T extends IOInstance<T>> long sizeOfUnknown(DataProvider provider, T instance, WordSpace wordSpace){
		var pip = StandardStructPipe.of(instance.getThisStruct());
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
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends StandardStructPipe<T>> void registerSpecialImpl(Struct<T> struct, Supplier<P> newType){
		StructPipe.registerSpecialImpl(struct, (Class<P>)(Object)StandardStructPipe.class, newType);
	}
	
	protected StandardStructPipe(Struct<T> type, PipeFieldCompiler<T, RuntimeException> compiler, boolean initNow){
		super(type, compiler, initNow);
	}
	public StandardStructPipe(Struct<T> type, boolean initNow){
		super(type, compiler(), initNow);
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
			if(field.streamUnpackedFields().flatMap(fields::streamDependentOn).findAny().isPresent()){
				var needsInstance = !field.streamUnpackedFields().allMatch(f -> f.isVirtual(IO));
				cmds.add(new CmdBuild.Read(needsInstance, field.needsIOPool()));
				continue;
			}
			
			var fixedSizeO = field.getSizeDescriptor().getFixed(WordSpace.BYTE);
			if(fixedSizeO.isPresent()){
				var fixedSize = fixedSizeO.getAsLong();
				if(!cmds.isEmpty() && cmds.getLast() instanceof CmdBuild.SkipFixed f){
					cmds.set(cmds.size() - 1, new CmdBuild.SkipFixed(f.bytes + fixedSize, f.extraFieldSkips + 1));
				}else{
					cmds.add(new CmdBuild.SkipFixed(fixedSize, 0));
				}
				continue;
			}
			
			cmds.add(new CmdBuild.Skip(field.needsIOPool()));
		}
		
		var buff = new byte[cmds.stream().mapToInt(CmdBuild::siz).sum()];
		var pos  = 0;
		for(var c : cmds){
			c.write(pos, buff);
			pos += c.siz();
		}
		
		assert pos == buff.length;
		
		boolean needsInstance = cmds.stream().anyMatch(c -> switch(c){
			case CmdBuild.Skip ignored -> false;
			case CmdBuild.SkipFixed ignored -> false;
			case CmdBuild.Read read -> read.needsInstance;
		});
		boolean needsIOPool = cmds.stream().anyMatch(c -> switch(c){
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
}
