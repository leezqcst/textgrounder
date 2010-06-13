///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2010 Taesun Moon, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////
package opennlp.textgrounder.gazetteers;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import opennlp.textgrounder.geo.CommandLineOptions;
import opennlp.textgrounder.topostructs.SmallLocation;

/**
 *
 * @author tsmoon
 */
public class GazetteerGenerator<E extends SmallLocation> {

    /**
     * kludge field to make instantiation of E possible within the class
     */
    protected E genericsKludgeFactor;
    /**
     *
     */
    protected static GazetteerEnum.GazetteerTypes gazType;
    /**
     *
     */
    protected static String gazPath;
    /**
     * 
     */
    protected boolean gazetteerRefresh;

    public GazetteerGenerator(CommandLineOptions options) {
        initialize(options);
    }

    public GazetteerGenerator(CommandLineOptions _options, E _genericsKludgeFactor) {
        initialize(_options);
        genericsKludgeFactor = _genericsKludgeFactor;
    }

    protected void initialize(CommandLineOptions options) {
        String gazTypeArg = options.getGazetteType().toLowerCase();
        if (gazTypeArg.startsWith("c")) {
            gazType = GazetteerEnum.GazetteerTypes.CG;
        } else if (gazTypeArg.startsWith("n")) {
            gazType = GazetteerEnum.GazetteerTypes.NGAG;
        } else if (gazTypeArg.startsWith("u")) {
            gazType = GazetteerEnum.GazetteerTypes.USGSG;
        } else if (gazTypeArg.startsWith("w")) {
            gazType = GazetteerEnum.GazetteerTypes.WG;
        } else if (gazTypeArg.startsWith("t")) {
            gazType = GazetteerEnum.GazetteerTypes.TRG;
        } else {
            System.err.println("Error: unrecognized gazetteer type: " + gazTypeArg);
            System.err.println("Please enter w, c, u, g, or t.");
            System.exit(0);
            //myGaz = new WGGazetteer();
        }

        gazetteerRefresh = options.getGazetteerRefresh();
        gazPath = options.getGazetteerPath();
    }

    public Gazetteer<E> generateGazetteer() {
        Gazetteer<E> gazetteer = null;
        try {
            switch (gazType) {
                case CG:
                    gazetteer = new CensusGazetteer<E>(gazPath);
                    break;
                case NGAG:
                    gazetteer = new NGAGazetteer<E>(gazPath);
                    break;
                case USGSG:
                    gazetteer = new USGSGazetteer<E>(gazPath);
                    break;
                case WG:
                    gazetteer = new WGGazetteer<E>(gazPath);
                    break;
                case TRG:
                    gazetteer = new TRGazetteer<E>(gazPath, gazetteerRefresh);
                    break;
            }
        } catch (FileNotFoundException ex) {
            Logger.getLogger(GazetteerGenerator.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        } catch (IOException ex) {
            Logger.getLogger(GazetteerGenerator.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        } catch (ClassNotFoundException ex) {
            Logger.getLogger(GazetteerGenerator.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        } catch (SQLException ex) {
            Logger.getLogger(GazetteerGenerator.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }
        gazetteer.genericsKludgeFactor = genericsKludgeFactor;
        return gazetteer;
    }

    /**
     * @return the genericsKludgeFactor
     */
    public E getGenericsKludgeFactor() {
        return genericsKludgeFactor;
    }

    /**
     * @param genericsKludgeFactor the genericsKludgeFactor to set
     */
    public void setGenericsKludgeFactor(E genericsKludgeFactor) {
        this.genericsKludgeFactor = genericsKludgeFactor;
    }
}
