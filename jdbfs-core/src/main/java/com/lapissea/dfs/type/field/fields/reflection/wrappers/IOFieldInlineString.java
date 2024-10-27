package com.lapissea.dfs.type.field.fields.reflection.wrappers;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.text.AutoText;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldWrapper;
import com.lapissea.dfs.type.string.StringifySettings;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class IOFieldInlineString<CTyp extends IOInstance<CTyp>> extends IOFieldWrapper<CTyp, String>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<String>{
		public Usage(){ super(String.class, Set.of(IOFieldInlineString.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, String> create(FieldAccessor<T> field){
			return new IOFieldInlineString<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(Behaviour.of(IONullability.class, BehaviourSupport::ioNullability));
		}
	}
	
	public IOFieldInlineString(FieldAccessor<CTyp> accessor){
		super(accessor);
		
		
		var desc = AutoText.PIPE.getSizeDescriptor();
		
		initSizeDescriptor(SizeDescriptor.Unknown.of(
			desc.getWordSpace(),
			nullable()? 0 : desc.getMin(),
			desc.getMax(),
			(ioPool, prov, inst) -> {
				var val = getWrapped(ioPool, inst);
				if(val == null){
					if(nullable()) return 0;
					throw new NullPointerException();
				}
				return AutoText.PIPE.calcUnknownSize(prov, val, desc.getWordSpace());
			}
		));
	}
	
	private AutoText getWrapped(VarPool<CTyp> ioPool, CTyp instance){
		var raw = get(ioPool, instance);
		if(raw == null) return null;
		return new AutoText(raw);
	}
	
	@Override
	protected String defaultValue(){ return ""; }
	
	@Override
	protected void writeValue(DataProvider provider, ContentWriter dest, String value) throws IOException{
		AutoText.STR_PIPE.write(provider, dest, value);
	}
	@Override
	protected String readValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		return AutoText.STR_PIPE.readNew(provider, src, genericContext);
	}
	@Override
	protected void skipValue(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		AutoText.STR_PIPE.skip(provider, src, genericContext);
	}
	
	@Override
	protected void throwInformativeFixedSizeError(){
		//TODO
		throw new RuntimeException("Strings do not support fixed size yet. In future a max string size will be defined by the user if they wish for it to be fixed size compatible.");
	}
	
	@Override
	public Optional<String> instanceToString(VarPool<CTyp> ioPool, CTyp instance, StringifySettings settings){
		var val = get(ioPool, instance);
		if(val == null || val.length() == 0) return Optional.empty();
		return Optional.of('"' + val + '"');
	}
}
