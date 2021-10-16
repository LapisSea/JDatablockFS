package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public class IOFieldInlineString<CTyp extends IOInstance<CTyp>> extends IOField<CTyp, String>{
	
	private final SizeDescriptor<CTyp> descriptor;
	private final StructPipe<AutoText> instancePipe;
	
	public IOFieldInlineString(FieldAccessor<CTyp> accessor){
		super(accessor);
		
		
		instancePipe=ContiguousStructPipe.of(AutoText.class);
		
		int nullSize=nullable()?1:0;
		
		var desc=instancePipe.getSizeDescriptor();
		descriptor=new SizeDescriptor.Unknown<>(
			desc.getWordSpace(),
			nullable()?nullSize:desc.getMin(),
			Utils.addIfBoth(OptionalLong.of(nullSize), desc.getMax()),
			inst->{
				var val=getWrapped(inst);
				if(val==null){
					if(nullable()) return nullSize;
					throw new NullPointerException();
				}
				return desc.calcUnknown(val)+nullSize;
			}
		);
	}
	
	private AutoText getWrapped(CTyp instance){
		var raw=get(instance);
		if(raw==null) return null;
		return new AutoText(raw);
	}
	
	@Override
	public String get(CTyp instance){
		String value=super.get(instance);
		return switch(getNullability()){
			case NOT_NULL -> requireValNN(value);
			case NULLABLE -> value;
			case DEFAULT_IF_NULL -> {
				if(value==null){
					var newVal="";
					set(instance, newVal);
					yield newVal;
				}
				yield value;
			}
		};
	}
	
	@Override
	public void set(CTyp instance, String value){
		super.set(instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public SizeDescriptor<CTyp> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public List<IOField<CTyp, ?>> write(ChunkDataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val=getWrapped(instance);
		if(nullable()){
			try(var flags=new FlagWriter.AutoPop(NumberSize.BYTE, dest)){
				flags.writeBoolBit(val==null);
			}
			if(val==null) return List.of();
		}
		instancePipe.write(provider, dest, val);
		return List.of();
	}
	
	private AutoText readNew(ChunkDataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		if(nullable()){
			try(var flags=FlagReader.read(src, NumberSize.BYTE)){
				if(flags.readBoolBit()){
					return null;
				}
			}
		}
		
		return instancePipe.readNew(provider, src, genericContext);
	}
	
	@Override
	public void read(ChunkDataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		var text=readNew(provider, src, genericContext);
		set(instance, text==null?null:text.getData());
	}
	
	@Override
	public void skipRead(ChunkDataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		readNew(provider, src, genericContext);
	}
	
	@Override
	protected void throwInformativeFixedSizeError(){
		//TODO
		throw new RuntimeException("Strings do not support fixed size yet. In future a max string size will be defined by the user if they wish for it to be fixed size compatible.");
	}
}
