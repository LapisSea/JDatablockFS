package demos.world;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

import java.util.ArrayList;
import java.util.List;

@IOValue
public class Entity extends IOInstance.Managed<Entity>{
	public Vec2d               pos       = new Vec2d();
	public List<InventorySlot> inventory = new ArrayList<>();
}
