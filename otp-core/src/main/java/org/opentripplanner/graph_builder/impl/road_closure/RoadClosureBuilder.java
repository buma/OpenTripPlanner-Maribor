/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.opentripplanner.graph_builder.impl.road_closure;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LineString;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import lombok.Setter;
import org.opentripplanner.common.NonRepeatingTimePeriod;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.graph_builder.impl.map.StreetMatcher;
import org.opentripplanner.graph_builder.services.GraphBuilder;
import org.opentripplanner.routing.edgetype.PlainStreetEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author mabu
 */
public class RoadClosureBuilder implements GraphBuilder {
    
    private static final Logger log = LoggerFactory.getLogger(RoadClosureBuilder.class);
    
    
    private File _path;
    
    StreetMatcher streetMatcher;
    Graph graph;
    
    public void setPath(File path) {
        _path = path;
    }
    
    private File[] getTSVFiles() {
        return _path.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".tsv");
            }
        });
    }
    
    private File[] getTXTFiles() {
        return _path.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".txt");
            }
        });
    }
    
    private LineString createMatchingGeometry(Geometry geometry, NonRepeatingTimePeriod period) {
        //FIXME: biking and walking can go through closed roads
        //Matches coordinates with street network
        List<Edge> edges = streetMatcher.match(geometry);
        if (edges != null) {
            log.info("Matched with {} edges.", edges.size());

            List<Coordinate> allCoordinates = new ArrayList<Coordinate>();
            for (Edge e : edges) {
                allCoordinates.addAll(Arrays.asList(e.getGeometry().getCoordinates()));
                PlainStreetEdge pse = (PlainStreetEdge) e;
                pse.setRoadClosedPeriod(period);
                //log.debug("Closed road:{}", pse);
                //pse.setName(pse.getName() + " TOTA");
            }
            Coordinate[] coordinateArray = new Coordinate[allCoordinates.size()];
            return GeometryUtils.getGeometryFactory().createLineString(allCoordinates.toArray(coordinateArray));
        } else {
            return null;
        }
    }
    
    private RoadClosure readTsv(File filepath) throws FileNotFoundException, IOException, Exception {
        log.info("Current file: {}", filepath.getName());
        List<Coordinate> coordinates = null;
        RoadClosureInfo roadClosureInfo = null;
        coordinates = new ArrayList<>();
        roadClosureInfo = new RoadClosureInfo();
        //reads coordinates
        try (BufferedReader br = new BufferedReader(new FileReader(filepath))) {
            //reads twice because first line are column names
            String line = br.readLine();
            line = br.readLine();
            String[] values = line.split("\\t", -1); // don't truncate empty fields

            while (line != null) {
                //ignores comments
                if (!line.startsWith("#") && !line.trim().isEmpty()) {
                    values = line.split("\\t", -1); // don't truncate empty fields

                    coordinates.add(new Coordinate(Double.parseDouble(values[2]), Double.parseDouble(values[1])));
                }
                line = br.readLine();

            }
        }
        //creates linestring from coordinates
        Coordinate[] coordArray = new Coordinate[coordinates.size()];
        LineString geometry = GeometryUtils.getGeometryFactory().createLineString(
                coordinates.toArray(coordArray));
        
        //reverses coordinates to close roads with edges in other direction
        Collections.reverse(coordinates);
        Coordinate[] coordArrayRev = new Coordinate[coordinates.size()];
        LineString geometryRev = GeometryUtils.getGeometryFactory().createLineString(
                coordinates.toArray(coordArrayRev));
        log.info("Read coordinates: {}", coordinates.size());

        //Reads closure start/stop
        String txtfilepath = filepath.getAbsolutePath().replace("tsv", "txt");
        try (BufferedReader br = new BufferedReader(new FileReader(txtfilepath))) {
            String line = br.readLine();

            String[] values = line.split("=", 2); // Split only on first =

            while (line != null) {
                values = line.split("=", 2); // Split only on first =
                roadClosureInfo.add(values[0], values[1]);
                line = br.readLine();

            }
        }
        //log.info("Read road info: {}", roadClosureInfo);

        //makes time period
        NonRepeatingTimePeriod rep = NonRepeatingTimePeriod.parseRoadClosure(
                roadClosureInfo.date_on, roadClosureInfo.date_off,
                roadClosureInfo.hour_on, roadClosureInfo.hour_off);

        if (new Date().getTime() > rep.getEndClosure()) {
            log.info("Road closure ends before today. Ignoring");
            return null;
        }

        RoadClosure roadClosure = new RoadClosure();
        roadClosure.period = rep;

        roadClosure.description = roadClosureInfo.description;
        roadClosure.url = roadClosureInfo.url;
        roadClosure.closureStart = new Date(rep.getStartClosure());
        roadClosure.closureEnd = new Date(rep.getEndClosure());
        roadClosure.title = roadClosureInfo.title;
        roadClosure.full = roadClosureInfo.full;
        roadClosure.show_only = roadClosureInfo.showOnly;
        
        
        //Show only are roads for which geometry isn't fully correct
        //For these roads we only show closures and don't close them in a graph
        if (roadClosure.show_only) {
            roadClosure.geometry = geometry;
            log.info("Made roadClosure for show: {}", roadClosure);
            return roadClosure;
        }
        
        //We have way IDS
        if (roadClosureInfo.way_id != null) {
            List<String> edgeLabels = new ArrayList<String>();
            if (roadClosureInfo.way_id.contains(",")) {
                String[] way_ids = roadClosureInfo.way_id.split(",");
                for(int i = 0; i < way_ids.length; i++) {
                    edgeLabels.add(String.format("osm:way:%s", way_ids[i]));
                }
            } else {
                edgeLabels.add(String.format("osm:way:%s", roadClosureInfo.way_id));
            }

            Set<String> foundedEdges = new HashSet<String>(edgeLabels.size());
            boolean foundAll = false;
            List<Coordinate> allCoordinates = new ArrayList<Coordinate>();
            for (Vertex gv : graph.getVertices()) {
                if(foundAll) {
                    break;
                }
                for (Edge edge : gv.getOutgoingStreetEdges()) {
                    if (foundAll) {
                        break;
                    }
                    PlainStreetEdge pse = (PlainStreetEdge) edge;
                    if (pse.getLabel() != null) {
                        //We are on vertex with reverse of our founded edge
                        //we already inserted it when we found it the first time
                        if (foundedEdges.contains(pse.getLabel())) {
                            continue;
                        }
                        for (String edgeLabel : edgeLabels) {
                            if (pse.getLabel().equals(edgeLabel)) {
                                foundedEdges.add(edgeLabel);

                                allCoordinates.addAll(Arrays.asList(edge.getGeometry().getCoordinates()));
                                pse.setRoadClosedPeriod(rep);

                                log.debug("Closed road with ID:{}", pse);
                                //Found edge for same street in reverse direction
                                for (Edge edge_inc: gv.getIncoming()) {
                                    if(edge_inc.isReverseOf(edge)) {
                                        PlainStreetEdge pse_inc = (PlainStreetEdge) edge_inc;
                                        pse_inc.setRoadClosedPeriod(rep);
                                        break;
                                    }
                                }
                                //We found all the edges
                                if(foundedEdges.size()==edgeLabels.size()) {
                                    foundAll = true;
                                }
                                //only one edgeName can be found in one edge
                                break;
                            }
                        }
                    }
                }
            }
            Coordinate[] coordinateArray = new Coordinate[allCoordinates.size()];
            LineString ls = GeometryUtils.getGeometryFactory().createLineString(allCoordinates.toArray(coordinateArray));
            roadClosure.geometry = ls;
            log.info("Made roadClosure: {}", roadClosure);
            return roadClosure;
        }

        LineString ls = createMatchingGeometry(geometry, rep);
        LineString lsRev = createMatchingGeometry(geometryRev, rep);
        if (ls != null && lsRev != null) {
            roadClosure.geometry = ls;
            log.info("Made roadClosure: {}", roadClosure);
            return roadClosure;
        } else if (ls != null) {
            roadClosure.geometry = ls;
            log.info("Made roadClosure: {}", roadClosure);
            log.warn("Reverse wasn't matched.");
            return roadClosure;
        } else if (lsRev != null) {
            roadClosure.geometry = lsRev;
            log.info("Made roadClosure: {}", roadClosure);
            log.warn("Normal wasn't matched.");
            return roadClosure;
        } else {
            log.warn("No edges could be matched!");
            return null;

        }
    }


    @Override
    public void buildGraph(Graph graph, HashMap<Class<?>, Object> extra) {
        log.info("Building road Closures");
        log.info("Path is:{}", _path);
        this.graph = graph;
        streetMatcher = new StreetMatcher(this.graph);
        int addedClosures = 0;

        RoadClosureService roadClosureService = new RoadClosureService();
        graph.putService(RoadClosureService.class, roadClosureService);
        File[] tsvFiles = this.getTSVFiles();
        for (int i = 0; i < tsvFiles.length; i++) {
            try {

                RoadClosure roadClosure = this.readTsv(tsvFiles[i]);
                if (roadClosure != null) {
                    roadClosureService.addRoadClosure(roadClosure);
                    addedClosures++;
                }

            } catch (Exception ex) {
                java.util.logging.Logger.getLogger(RoadClosureBuilder.class.getName()).log(Level.SEVERE, "Road closure file wasn't found", ex);
            }
        }
        log.info("Added {} road closures", addedClosures);
    }

    @Override
    public List<String> provides() {
        return Arrays.asList("road closures");
    }

    @Override
    public List<String> getPrerequisites() {
        return Arrays.asList("streets");
    }

    @Override
    public void checkInputs() {
        File[] txtFiles = this.getTXTFiles();
        File[] tsvFiles = this.getTSVFiles();
        if (txtFiles.length != tsvFiles.length) {
            throw new RuntimeException(
                    String.format("TXT and TSV numbers of files should be the same! TXT:%d, TSV:%d",
                            txtFiles.length, tsvFiles.length));
        }
        for (int i = 0; i < tsvFiles.length; i++) {

            if (!tsvFiles[i].canRead()) {
                throw new RuntimeException("Can't read RoadClosure tsv file: " + tsvFiles[i]);
            }
            
            String txtfilepath = tsvFiles[i].getAbsolutePath().replace("tsv", "txt");
            File txtFile = new File(txtfilepath);
            if (!txtFile.canRead()) {
                throw new RuntimeException("Can't read RoadClosure txt file: " + txtFile);
            }
        }
    }
    
}
