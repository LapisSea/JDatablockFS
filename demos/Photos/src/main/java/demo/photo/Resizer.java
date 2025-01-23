package demo.photo;

import com.lapissea.vec.Vec2f;
import com.lapissea.vec.interf.IVec2iR;

interface Resizer{
	IVec2iR resize(Vec2f value, IVec2iR size);
}
