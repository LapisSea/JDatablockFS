package demo.photo;

import java.util.function.Predicate;

public enum TextureType{
	COLOR(str -> "COLOR".startsWith(str) || "DIFFUSE".startsWith(str) || str.equals("BASE_COLOR") || str.equals("BASECOLOR") || str.equals("ALBEDO") || str.matches("^VAR[0-9]*$") || str.matches("^COL_VAR[0-9]*$")),
	DISPLACE(str -> "DISPLACEMENT".startsWith(str) || "HEIGHT".equals(str)),
	NORMAL(str -> "NORMAL".startsWith(str) || str.matches("^NRM[0-9]*$")),
	ROUGHNESS(str -> "ROUGHNESS".startsWith(str) || "RGH".equals(str)),
	AMBIENT_OCCLUSION("AO"::equals),
	ROUGH_AMBIENT_OCCLUSION("ROUGH_AO"::equals),
	MASK(str -> str.matches("^MASK[0-9]*$") || "OPACITY".equals(str)),
	ID("ID"::equals),
	METALNESS(str -> "METALNESS".startsWith(str) || "METALLIC".equals(str)),
	EMISSION("EMISSION"::startsWith),
	SPECULAR("SPECULAR"::startsWith),
	BUMP("BUMP"::equals),
	PREVIEW("PREVIEW"::equals);
	
	public final Predicate<String> check;
	
	TextureType(Predicate<String> check){
		this.check = check;
	}
}
