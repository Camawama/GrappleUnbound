package net.camacraft.grappleunbound.network;

import net.camacraft.grappleunbound.GrappleMod;
import net.camacraft.grappleunbound.config.GrappleConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Comparator;

/*
 * This file is part of GrappleMod.

    GrappleMod is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    GrappleMod is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with GrappleMod.  If not, see <http://www.gnu.org/licenses/>.
 */

public class LoggedInMessage extends BaseMessageClient {
    GrappleConfig.Config conf;

    public LoggedInMessage(FriendlyByteBuf buf) {
    	super(buf);
    }
    
    public LoggedInMessage(GrappleConfig.Config serverconf) {
    	this.conf = serverconf;
    }
    
    public <T> void decodeClass(FriendlyByteBuf buf, Class<T> theClass, T theObject) {
    	Field[] fields = theClass.getDeclaredFields();
    	Arrays.sort(fields, new Comparator<Field>() {
    	    @Override
    	    public int compare(Field o1, Field o2) {
    	        return o1.getName().compareTo(o2.getName());
    	    }
    	});
    	
    	for (Field field : fields) {
    		Type fieldtype = field.getGenericType();
    		try {
        		if (fieldtype.getTypeName().equals("int")) {
        			field.setInt(theObject, buf.readInt());
        		} else if (fieldtype.getTypeName().equals("double")) {
        			field.setDouble(theObject, buf.readDouble());
        		} else if (fieldtype.getTypeName().equals("boolean")) {
        			field.setBoolean(theObject, buf.readBoolean());
        		} else if (fieldtype.getTypeName().equals("java.lang.String")) {
        			field.set(theObject, buf.readUtf());
        		} else if (field.getType() != null && Object.class.isAssignableFrom(field.getType())) {
        			Class newClass = field.getType();
        			decodeClass(buf, newClass, newClass.cast(field.get(theObject)));
        		} else {
        			GrappleMod.LOGGER.warn("Unknown config field type");
        			GrappleMod.LOGGER.warn(fieldtype.getTypeName());
        		}
    		} catch (IllegalAccessException e) {
    			GrappleMod.LOGGER.error("Config sync reflection error", e);
    		}
    	}
    }

    public void decode(FriendlyByteBuf buf) {
    	Class<GrappleConfig.Config> confclass = GrappleConfig.Config.class;
    	this.conf = new GrappleConfig.Config();
    	
    	decodeClass(buf, confclass, this.conf);
    }

    public <T> void encodeClass(FriendlyByteBuf buf, Class<T> theClass, T theObject) {
    	Field[] fields = theClass.getDeclaredFields();
    	Arrays.sort(fields, new Comparator<Field>() {
    	    @Override
    	    public int compare(Field o1, Field o2) {
    	        return o1.getName().compareTo(o2.getName());
    	    }
    	});
    	
    	for (Field field : fields) {
    		Type fieldtype = field.getGenericType();
    		try {
        		if (fieldtype.getTypeName().equals("int")) {
        			buf.writeInt(field.getInt(theObject));
        		} else if (fieldtype.getTypeName().equals("double")) {
        			buf.writeDouble(field.getDouble(theObject));
        		} else if (fieldtype.getTypeName().equals("boolean")) {
        			buf.writeBoolean(field.getBoolean(theObject));
        		} else if (fieldtype.getTypeName().equals("java.lang.String")) {
        			// length-prefixed UTF-8 (platform independent; byte length matches what's written)
        			buf.writeUtf((String) field.get(theObject));
        		} else if (field.getType() != null && Object.class.isAssignableFrom(field.getType())) {
        			Class newClass = field.getType();
        			encodeClass(buf, newClass, newClass.cast(field.get(theObject)));
        		} else {
        			GrappleMod.LOGGER.warn("Unknown config field type");
        			GrappleMod.LOGGER.warn(fieldtype.getTypeName());
        		}
    		} catch (IllegalAccessException e) {
    			GrappleMod.LOGGER.error("Config sync reflection error", e);
    		}
    	}
    }

    public void encode(FriendlyByteBuf buf) {
    	Class<GrappleConfig.Config> confclass = GrappleConfig.Config.class;
    	encodeClass(buf, confclass, this.conf);
    }

	@Override
	public void processMessage(NetworkEvent.Context ctx) {
    	GrappleConfig.setServerOptions(this.conf);
    }
}
