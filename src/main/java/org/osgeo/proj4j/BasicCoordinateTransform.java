package org.osgeo.proj4j;

import org.osgeo.proj4j.datum.*;

/**
 * Represents the operation of transforming
 * a {@link ProjCoordinate} from one {@link CoordinateReferenceSystem}
 * into a different one, using reprojection and datum conversion
 * as required.
 * <p>
 * Computing the transform involves the following steps:
 * <ul>
 * <li>If the source coordinate is in a projected coordinate system,
 * it is inverse-projected into a geographic coordinate system
 * based on the source datum
 * <li>If the source and target {@link Datum}s are different,
 * the source geographic coordinate is converted
 * from the source to the target datum
 * as accurately as possible
 * <li>If the target coordinate system is a projected coordinate system,
 * the converted geographic coordinate is projected into a projected coordinate.
 * </ul>
 * Symbolically this can be presented as:
 * <pre>
 * [ SrcProjCRS {InverseProjection} ] SrcGeoCRS [ {Datum Conversion} ] TgtGeoCRS [ {Projection} TgtProjCRS ]
 * </pre>
 * <p>
 * Information about the transformation procedure is pre-computed
 * and cached in this object for efficient computation.
 *
 * @author Martin Davis
 * @see CoordinateTransformFactory
 *
 */
public class BasicCoordinateTransform
    implements CoordinateTransform
{
    private final CoordinateReferenceSystem srcCRS;
    private final CoordinateReferenceSystem tgtCRS;

    // precomputed information
    private final boolean doInverseProjection;
    private final boolean doForwardProjection;
    private final boolean doDatumTransform;
    private final boolean transformViaGeocentric;
    private GeocentricConverter srcGeoConv;
    private GeocentricConverter tgtGeoConv;

    /**
     * Creates a transformation from a source {@link CoordinateReferenceSystem}
     * to a target one.
     *
     * @param srcCRS the source CRS to transform from
     * @param tgtCRS the target CRS to transform to
     */
    public BasicCoordinateTransform(CoordinateReferenceSystem srcCRS,
                                    CoordinateReferenceSystem tgtCRS)
    {
        this.srcCRS = srcCRS;
        this.tgtCRS = tgtCRS;

        // compute strategy for transformation at initialization time, to make transformation more efficient
        // this may include precomputing sets of parameters

        doInverseProjection = (srcCRS != CoordinateReferenceSystem.CS_GEO);
        doForwardProjection = (tgtCRS != CoordinateReferenceSystem.CS_GEO);
        doDatumTransform = doInverseProjection && doForwardProjection
            && srcCRS.getDatum() != tgtCRS.getDatum();

        if (doDatumTransform) {

            boolean isEllipsoidEqual = srcCRS.getDatum().getEllipsoid().isEqual(tgtCRS.getDatum().getEllipsoid());
            transformViaGeocentric = ! isEllipsoidEqual || srcCRS.getDatum().hasTransformToWGS84()
                    || tgtCRS.getDatum().hasTransformToWGS84();
            
            if (transformViaGeocentric) {
                srcGeoConv = new GeocentricConverter(srcCRS.getDatum().getEllipsoid());
                tgtGeoConv = new GeocentricConverter(tgtCRS.getDatum().getEllipsoid());

                if (srcCRS.getDatum().getTransformType() == Datum.TYPE_GRIDSHIFT) {
                    srcGeoConv.overrideWithWGS84Params();
                }

                if (tgtCRS.getDatum().getTransformType() == Datum.TYPE_GRIDSHIFT) {
                    tgtGeoConv.overrideWithWGS84Params();
                }
            }

        } else {
        	transformViaGeocentric=false;
        }
    }

    @Override
	public CoordinateReferenceSystem getSourceCRS()
    {
        return srcCRS;
    }

    @Override
	public CoordinateReferenceSystem getTargetCRS()
    {
        return tgtCRS;
    }


    /**
     * Transforms a coordinate from the source {@link CoordinateReferenceSystem}
     * to the target one.
     *
     * @param src the input coordinate to be transformed
     * @param tgt the transformed coordinate
     * @return the target coordinate which was passed in
     *
     * @throws Proj4jException if a computation error is encountered
     */
    // transform corresponds to the pj_transform function in proj.4
    @Override
	public ProjCoordinate transform( ProjCoordinate src, ProjCoordinate tgt )
        throws Proj4jException
    {
    	tgt.setValue(src);
        srcCRS.getProjection().getAxisOrder().toENU(tgt);

        // NOTE: this method may be called many times, so needs to be as efficient as possible
        if (doInverseProjection) {
            // inverse project to geographic
            srcCRS.getProjection().inverseProjectRadians(tgt, tgt);
        }

        srcCRS.getProjection().getPrimeMeridian().toGreenwich(tgt);

        // fixes bug where computed Z value sticks around
        tgt.clearZ();

        if (doDatumTransform) {
            datumTransform(tgt);
        }

        tgtCRS.getProjection().getPrimeMeridian().fromGreenwich(tgt);

        if (doForwardProjection) {
            // project from geographic to planar
            tgtCRS.getProjection().projectRadians(tgt, tgt);
        }

        tgtCRS.getProjection().getAxisOrder().fromENU(tgt);

        return tgt;
    }

    /**
     *
     * Input:  long/lat/z coordinates in radians in the source datum
     * Output: long/lat/z coordinates in radians in the target datum
     *
     * @param pt the point containing the input and output values
     */
    private void datumTransform(ProjCoordinate pt)
    {
        /* -------------------------------------------------------------------- */
        /*      Short cut if the datums are identical.                          */
        /* -------------------------------------------------------------------- */
        if (srcCRS.getDatum().isEqual(tgtCRS.getDatum())
                || srcCRS.getDatum().getTransformType() == Datum.TYPE_UNKNOWN
                || tgtCRS.getDatum().getTransformType() == Datum.TYPE_UNKNOWN)
            return;

        if (srcCRS.getDatum().getTransformType() == Datum.TYPE_GRIDSHIFT) {
            srcCRS.getDatum().shift(pt);
        }

        /* ==================================================================== */
        /*      Do we need to go through geocentric coordinates?                */
        /* ==================================================================== */
        if (transformViaGeocentric) {
            /* -------------------------------------------------------------------- */
            /*      Convert to geocentric coordinates.                              */
            /* -------------------------------------------------------------------- */
            srcGeoConv.convertGeodeticToGeocentric( pt );

            /* -------------------------------------------------------------------- */
            /*      Convert between datums.                                         */
            /* -------------------------------------------------------------------- */
            if( srcCRS.getDatum().hasTransformToWGS84() ) {
                srcCRS.getDatum().transformFromGeocentricToWgs84( pt );
            }

            if( tgtCRS.getDatum().hasTransformToWGS84() ) {
                tgtCRS.getDatum().transformToGeocentricFromWgs84( pt );
            }

            /* -------------------------------------------------------------------- */
            /*      Convert back to geodetic coordinates.                           */
            /* -------------------------------------------------------------------- */
            tgtGeoConv.convertGeocentricToGeodetic( pt );
        }


        if (tgtCRS.getDatum().getTransformType() == Datum.TYPE_GRIDSHIFT) {
            tgtCRS.getDatum().inverseShift(pt);
        }
    }

}
