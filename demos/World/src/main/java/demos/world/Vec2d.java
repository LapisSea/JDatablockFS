package demos.world;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

@IOValue
public class Vec2d extends IOInstance.Managed<Vec2d>{
	
	public float x, y;
	
	public Vec2d(){ }
	public Vec2d(float x, float y){
		this.x = x;
		this.y = y;
	}
	
	
}
