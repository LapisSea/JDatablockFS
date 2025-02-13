package demo.photo;

import com.lapissea.vec.Vec2f;
import com.lapissea.vec.Vec2i;
import com.lapissea.vec.interf.IVec2iR;

public enum ResizeType implements Resizer{
	
	NONE("#", 0, (value, size) -> size),
	PIXELS("#px", 256, (value, size) -> new Vec2i(size).min((int)value.x(), (int)value.y())),
	PERCENT("#%", .05, (value, size) -> new Vec2i((int)(value.x()*size.x()), (int)(value.y()*size.y()))),
	;
	
	private final Resizer resizer;
	public final  String  format;
	public final  double  stepSize;
	
	ResizeType(String format, double stepSize, Resizer resizer){
		this.format = format;
		this.stepSize = stepSize;
		this.resizer = resizer;
	}
	
	@Override
	public IVec2iR resize(Vec2f value, IVec2iR size){
		return resizer.resize(value, size);
	}
}
