/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.model.json_serialization;

import java.io.IOException;


import org.geotools.geojson.geom.GeometryJSON;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.vividsolutions.jts.geom.Geometry;

public class GeoJSONSerializer extends JsonSerializer<Geometry> {

    private final int decimals;

    /**
     *
     * @param decimals Wanted number of decimal places in coordinates
     */
    public GeoJSONSerializer(int decimals) {
        this.decimals = decimals;
    }

    public GeoJSONSerializer() {
        decimals = 0;
    }

    @Override
    public void serialize(Geometry value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonProcessingException {
        
        GeometryJSON json;
        if (decimals > 0) {
            json = new GeometryJSON(decimals);
        } else {
            json = new GeometryJSON();
        }
        
        jgen.writeRawValue(json.toString(value));
    }

    @Override
    public Class<Geometry> handledType() {
        return Geometry.class;
    }
}
