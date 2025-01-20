package demo.photo;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

import java.lang.invoke.MethodHandles;

@IOValue
public class Photo extends IOInstance.Managed<Photo>{
	static{ Managed.allowFullAccess(MethodHandles.lookup()); }
	
	public String name;
	public byte[] data;
	
	private Photo(){ }
	public Photo(String name, byte[] data){
		this.name = name;
		this.data = data;
	}
	
	public String name(){
		return name;
	}
}
