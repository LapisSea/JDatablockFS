package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.util.UtilL;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public interface RegistryNode{
	interface InstanceOf<ValTyp> extends RegistryNode{
		Class<ValTyp> getType();
		@Override
		default boolean canCreate(Type type){
			return UtilL.instanceOf(Utils.typeToRaw(type), getType());
		}
		@Override
		<T extends IOInstance<T>> IOField<T, ? extends ValTyp> create(IFieldAccessor<T> field);
	}
	
	class Registry implements RegistryNode{
		
		private final List<RegistryNode> nodes=new ArrayList<>();
		
		public void register(RegistryNode node){
			nodes.add(node);
		}
		
		private MalformedStructLayout fail(Type type){
			throw new MalformedStructLayout("Unable to find implementation of "+IOField.class.getSimpleName()+" from "+type);
		}
		private RegistryNode find(Type type){
			for(var node : nodes){
				if(node.canCreate(type)) return node;
			}
			return null;
		}
		
		public void requireCanCreate(Type type){
			if(!canCreate(type)){
				throw fail(type);
			}
		}
		
		@Override
		public boolean canCreate(Type type){
			return find(type)!=null;
		}
		
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field){
			var node=find(field.getGenericType());
			if(node!=null) return node.create(field);
			throw fail(field.getGenericType());
		}
	}
	
	boolean canCreate(Type type);
	<T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field);
}
