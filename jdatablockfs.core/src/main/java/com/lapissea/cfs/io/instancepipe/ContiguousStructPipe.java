package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class ContiguousStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public static <T extends IOInstance<T>> long sizeOfUnknown(DataProvider provider, T instance, WordSpace wordSpace){
		var pip=ContiguousStructPipe.of(instance.getThisStruct());
		return pip.calcUnknownSize(provider, instance, wordSpace);
	}
	
	public static <T extends IOInstance<T>> ContiguousStructPipe<T> of(Class<T> type){
		return of(Struct.of(type));
	}
	public static <T extends IOInstance<T>> ContiguousStructPipe<T> of(Class<T> type, int minRequestedStage){
		return of(Struct.of(type), minRequestedStage);
	}
	public static <T extends IOInstance<T>> ContiguousStructPipe<T> of(Struct<T> struct){
		return of(ContiguousStructPipe.class, struct);
	}
	public static <T extends IOInstance<T>> ContiguousStructPipe<T> of(Struct<T> struct, int minRequestedStage){
		return of(ContiguousStructPipe.class, struct, minRequestedStage);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends ContiguousStructPipe<T>> void registerSpecialImpl(Struct<T> struct, Supplier<P> newType){
		StructPipe.registerSpecialImpl(struct, (Class<P>)(Object)ContiguousStructPipe.class, newType);
	}
	
	public ContiguousStructPipe(Struct<T> type, boolean runNow){
		super(type, runNow);
	}
	
	@Override
	protected List<IOField<T, ?>> initFields(){
		return IOFieldTools.stepFinal(getType().getFields(), List.of(
			IOFieldTools::dependencyReorder,
			IOFieldTools::mergeBitSpace
		));
	}
	
	@Override
	protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		writeIOFields(getSpecificFields(), ioPool, provider, dest, instance);
	}
	
	@Override
	protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		readIOFields(getSpecificFields(), ioPool, provider, src, instance, genericContext);
		return instance;
	}
	
	private sealed interface Cmd{
		record SkipFixed(long size) implements Cmd{}
		
		record Skip() implements Cmd{}
		
		record Read() implements Cmd{}
	}
	
	private record SkipData<T extends IOInstance<T>>(FieldSet<T> fields, Cmd[] cmds, boolean makeInst){
		static final NumberSize[] NONE=new NumberSize[0];
		
	}
	
	private SkipData<T> skipCache;
	
	private SkipData<T> skipData(){
		var s=skipCache;
		if(s==null) skipCache=s=calcSkip();
		return s;
	}
	private SkipData<T> calcSkip(){
		var report=createSizeReport(0);
		if(report.dynamic()) return null;
		
		FieldSet<T> fields=report.allFields();
		List<Cmd>   cmds  =new ArrayList<>(fields.size());
		
		for(IOField<T, ?> field : fields){
			
			if(field.streamUnpackedFields().flatMap(fields::streamDependentOn).findAny().isPresent()){
				cmds.add(new Cmd.Read());
				continue;
			}
			
			var fixed=field.getSizeDescriptor().getFixed(WordSpace.BYTE);
			if(fixed.isPresent()){
				var siz=fixed.getAsLong();
				if(cmds.get(cmds.size()-1) instanceof Cmd.SkipFixed f){
					cmds.set(cmds.size()-1, new Cmd.SkipFixed(f.size+siz));
				}else{
					cmds.add(new Cmd.SkipFixed(siz));
				}
				continue;
			}
			
			cmds.add(new Cmd.Skip());
		}
		
		return new SkipData<>(fields, cmds.toArray(Cmd[]::new), cmds.stream().anyMatch(c->c instanceof Cmd.Skip||c instanceof Cmd.Read));
	}
	
	@Override
	public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var skip=skipData();
		if(skip==null){
			readNew(provider, src, genericContext);
			return;
		}
		
		var pool=skip.makeInst?makeIOPool():null;
		var inst=skip.makeInst?getType().make():null;
		
		for(int i=0;i<skip.cmds.length;i++){
			switch(skip.cmds[i]){
				case Cmd.SkipFixed skipFixed -> src.skipExact(skipFixed.size);
				case Cmd.Read ignore -> skip.fields.get(i).read(pool, provider, src, inst, genericContext);
				case Cmd.Skip ignore -> skip.fields.get(i).skip(pool, provider, src, inst, genericContext);
				case null -> throw new NullPointerException();
			}
		}
	}
}
