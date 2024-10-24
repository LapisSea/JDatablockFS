package com.lapissea.dfs.objects;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.IOTypeDB;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;

import java.io.IOException;

@IOValue
public final class TypedReference extends IOInstance.Managed<TypedReference>{
	public static final Struct<TypedReference> STRUCT = Struct.of(TypedReference.class);
	
	private Reference ref;
	@IODependency.VirtualNumSize
	@IOValue.Unsigned
	private int       id;
	
	public TypedReference(){
		super(STRUCT);
		ref = Reference.NULL;
	}
	
	public TypedReference(Reference ref, int id){
		super(STRUCT);
		this.ref = ref;
		this.id = id;
	}
	
	public int getId(){
		return id;
	}
	public IOType getType(IOTypeDB db) throws IOException{
		return db.fromID(id);
	}
	public <T> Class<T> getType(IOTypeDB db, Class<T> root) throws IOException{
		var type = db.fromID(root, id);
		if(type == null){
			throw new IllegalStateException("Type of {id: " + id + ", root: " + root.getName() + "} does not exist");
		}
		return type;
	}
	
	public Reference getRef(){
		return ref;
	}
	
	public boolean isNull(){
		return ref.isNull();
	}
	
	public TypedReference withRef(Reference ref){
		return new TypedReference(ref, id);
	}
}
