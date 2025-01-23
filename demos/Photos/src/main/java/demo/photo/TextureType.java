package demo.photo;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.function.Predicate;

public enum TextureType{
	COLOR(str -> "COLOR".startsWith(str) || "DIFFUSE".startsWith(str) || Iters.of("BASE_COLOR", "BASECOLOR", "ALBEDO").anyEquals(str) || str.matches("^VAR[0-9]*$") || str.matches("^COL_VAR[0-9]*$")),
	DISPLACE(Iters.of("DISPLACEMENT", "HEIGHT")::anyEquals),
	NORMAL(str -> "NORMAL".startsWith(str) || str.matches("^NRM[0-9]*$")),
	ROUGHNESS(Iters.of("ROUGHNESS", "RGH")::anyEquals),
	AMBIENT_OCCLUSION(Iters.of("AO", "AMBIENTOCCLUSION", "AMBIENT_OCCLUSION")::anyEquals),
	ROUGH_AMBIENT_OCCLUSION("ROUGH_AO"::equals),
	MASK(str -> str.matches("^MASK[0-9]*$") || "OPACITY".equals(str)),
	ID("ID"::equals),
	METALNESS(Iters.of("METALNESS", "METALLIC")::anyEquals),
	EMISSION("EMISSION"::startsWith),
	SPECULAR("SPECULAR"::startsWith),
	BUMP("BUMP"::equals),
	PREVIEW("PREVIEW"::equals);
	
	public final Predicate<String> check;
	
	TextureType(Predicate<String> check){
		this.check = check;
	}
}
