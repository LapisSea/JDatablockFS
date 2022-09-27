package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.util.Objects;

public class IOFieldInlineString<CTyp extends IOInstance<CTyp>> extends NullFlagCompanyField<CTyp, String>{
	
	private final SizeDescriptor<CTyp> descriptor;
	
	public IOFieldInlineString(FieldAccessor<CTyp> accessor){
		super(accessor);
		
		
		var desc=AutoText.PIPE.getSizeDescriptor();
		
		descriptor=SizeDescriptor.Unknown.of(
			desc.getWordSpace(),
			nullable()?0:desc.getMin(),
			desc.getMax(),
			(ioPool, prov, inst)->{
				var val=getWrapped(null, inst);
				if(val==null){
					if(nullable()) return 0;
					throw new NullPointerException();
				}
				return desc.calcUnknown(AutoText.PIPE.makeIOPool(), prov, val, desc.getWordSpace());
			}
		);
	}
	
	private AutoText getWrapped(VarPool<CTyp> ioPool, CTyp instance){
		var raw=get(ioPool, instance);
		if(raw==null) return null;
		return new AutoText(raw);
	}
	
	@Override
	public String get(VarPool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance, ()->"");
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, String value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public SizeDescriptor<CTyp> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		if(nullable()&&getIsNull(ioPool, instance)) return;
		var val=getWrapped(ioPool, instance);
		if(val==null&&!nullable()){
			throw new NullPointerException();
		}
		AutoText.PIPE.write(provider, dest, val);
	}
	
	private AutoText readNew(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			if(getIsNull(ioPool, instance)) return null;
		}
		
		return AutoText.PIPE.readNew(provider, src, genericContext);
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		var text=readNew(ioPool, provider, src, instance, genericContext);
		set(ioPool, instance, text==null?null:text.getData());
	}
	
	@Override
	public void skipRead(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		readNew(ioPool, provider, src, instance, genericContext);
	}
	
	@Override
	protected void throwInformativeFixedSizeError(){
		//TODO
		throw new RuntimeException("Strings do not support fixed size yet. In future a max string size will be defined by the user if they wish for it to be fixed size compatible.");
	}
}
