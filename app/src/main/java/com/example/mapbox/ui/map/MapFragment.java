package com.example.mapbox.ui.map;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.mapbox.R;
import com.mapbox.android.core.permissions.PermissionsListener;
import com.mapbox.android.core.permissions.PermissionsManager;
import com.mapbox.api.directions.v5.models.DirectionsResponse;
import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.api.geocoding.v5.models.CarmenFeature;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.geojson.Point;
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.location.LocationComponent;
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions;
import com.mapbox.mapboxsdk.location.modes.CameraMode;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.Style;
import com.mapbox.mapboxsdk.plugins.places.autocomplete.PlaceAutocomplete;
import com.mapbox.mapboxsdk.plugins.places.autocomplete.model.PlaceOptions;
import com.mapbox.mapboxsdk.style.layers.SymbolLayer;
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource;
import com.mapbox.services.android.navigation.ui.v5.route.NavigationMapRoute;
import com.mapbox.services.android.navigation.v5.navigation.NavigationRoute;

import org.jetbrains.annotations.NotNull;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconAllowOverlap;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconIgnorePlacement;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconImage;

public class MapFragment extends Fragment implements com.mapbox.mapboxsdk.maps.OnMapReadyCallback,
        PermissionsListener, MapboxMap.OnMapClickListener {
    private static final int SEARCH_REQUEST_CODE = 765;
    private static String TAG = MapFragment.class.getSimpleName();
    private MapView mMapView;
    private MapboxMap mMapboxMap;
    private LocationComponent locationComponent;
    private DirectionsRoute currentRoute;
    private NavigationMapRoute navigationMapRoute;
    private TextView pickupTextView;
    private TextView destinationTextView;

    private IconInformation pickupIconInfo, destinationIconInfo;
    private LocationMode selectionMode = LocationMode.START;
    private MapInteractionListener mListener;

    public enum LocationMode {START, DESTINATION}

    private static final class IconInformation {
        public String iconId, symbolLayerId, geoJsonLayerId;
        public Drawable icon;

        public IconInformation(Drawable drawable, String iconId, String symbolLayerId, String geoJsonLayerId) {
            this.icon = drawable;
            this.iconId = iconId;
            this.symbolLayerId = symbolLayerId;
            this.geoJsonLayerId = geoJsonLayerId;
        }
    }

    public static MapFragment newInstance() {
        return new MapFragment();
    }

    public void setSelectionMode(@NonNull LocationMode mode) {
        selectionMode = mode;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View contentView = inflater.inflate(R.layout.map_fragment, container, false);
        pickupIconInfo = new IconInformation(getResources().getDrawable(R.drawable.ic_location),
                "pickup_icon", "pickup_layer", "pickup_geo_layer");
        destinationIconInfo = new IconInformation(getResources().getDrawable(R.drawable.ic_new_location),
                "destination_icon", "destination_layer", "destination_geo_layer");
        mMapView = contentView.findViewById(R.id.mapView);
        mMapView.onCreate(savedInstanceState);
        mMapView.getMapAsync(this);

        pickupTextView = contentView.findViewById(R.id.id_pick_up_location_text);
        destinationTextView = contentView.findViewById(R.id.id_destination_location_text);
        return contentView;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof MapInteractionListener)
            mListener = (MapInteractionListener) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    /**
     * Called when the map is ready to be used.
     *
     * @param mapboxMap An instance of MapboxMap associated with the {@link MapFragment} or
     *                  {@link MapView} that defines the callback.
     */
    @Override
    public void onMapReady(@NonNull MapboxMap mapboxMap) {
        mMapboxMap = mapboxMap;
        // Uncomment the next 2 lines to remove the watermarks
//        mMapboxMap.getUiSettings().setAttributionEnabled(false);
//        mMapboxMap.getUiSettings().setLogoEnabled(false);
        mMapboxMap.setStyle(new Style.Builder().fromUri("mapbox://styles/mapbox/cjf4m44iw0uza2spb3q0a7s41"), style -> {
            enableLocationComponent(style);
            addDestinationIconSymbolLayer(style);
            addPickupIconSymbolLayer(style);

            mMapboxMap.addOnMapClickListener(MapFragment.this);

            pickupTextView.setOnClickListener(v -> findLocation());
            destinationTextView.setOnClickListener(v -> findLocation());
        });
    }

    public void findLocation() {
        Intent intent = new PlaceAutocomplete.IntentBuilder()
                .accessToken(Mapbox.getAccessToken() != null ? Mapbox.getAccessToken() : getString(R.string.access_token))
                .placeOptions(PlaceOptions.builder()
                        .backgroundColor(Color.parseColor("#EEEEEE"))
                        .limit(10)
                        //.addInjectedFeature(home)
                        //.addInjectedFeature(work)
                        .build(PlaceOptions.MODE_CARDS))
                .build(getActivity());
        startActivityForResult(intent, SEARCH_REQUEST_CODE);
    }

    @SuppressLint("LogNotTimber")
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && requestCode == SEARCH_REQUEST_CODE) {
            // Retrieve selected location's CarmenFeature
            CarmenFeature selectedCarmenFeature = null;
            if (data != null) {
                selectedCarmenFeature = PlaceAutocomplete.getPlace(data);
                if (mListener != null)
                    mListener.onOnSearchResultAccepted(selectedCarmenFeature);
            }
            // Create a new FeatureCollection and add a new Feature to it using selectedCarmenFeature above.
            // Then retrieve and update the source designated for showing a selected location's symbol layer icon
            if (mMapboxMap != null) {
                Style style = mMapboxMap.getStyle();
                if (style != null) {
// Move map camera to the selected location
                    if (selectedCarmenFeature != null) {
                        if (selectionMode == LocationMode.START) {
                            pickupTextView.setText(selectedCarmenFeature.text());
                            GeoJsonSource source = style.getSourceAs(pickupIconInfo.geoJsonLayerId);
                            if (source != null) {
                                source.setGeoJson(FeatureCollection.fromFeatures(
                                        new Feature[]{Feature.fromJson(selectedCarmenFeature.toJson())}));
                            }
                        } else {
                            destinationTextView.setText(selectedCarmenFeature.text());
                            GeoJsonSource source = style.getSourceAs(destinationIconInfo.geoJsonLayerId);
                            if (source != null) {
                                source.setGeoJson(FeatureCollection.fromFeatures(
                                        new Feature[]{Feature.fromJson(selectedCarmenFeature.toJson())}));
                            }
                        }
                        mMapboxMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                                new CameraPosition.Builder()
                                        .target(new LatLng(((Point) selectedCarmenFeature.geometry()).latitude(),
                                                ((Point) selectedCarmenFeature.geometry()).longitude()))
                                        .zoom(14)
                                        .build()), 4000);
                    }
                }
            }
        }
    }

    @SuppressWarnings({"MissingPermission"})
    private void enableLocationComponent(@NonNull Style loadedMapStyle) {
        // Check if permissions are enabled and if not request
        if (PermissionsManager.areLocationPermissionsGranted(getContext())) {
            // Activate the MapboxMap LocationComponent to show user location
            // Adding in LocationComponentOptions is also an optional parameter
            locationComponent = mMapboxMap.getLocationComponent();
            locationComponent.activateLocationComponent(LocationComponentActivationOptions.builder(getContext(), loadedMapStyle).build());
            //locationComponent.activateLocationComponent(getContext(), loadedMapStyle);
            locationComponent.setLocationComponentEnabled(true);
            // Set the component's camera mode
            locationComponent.setCameraMode(CameraMode.TRACKING);
        } else {
            PermissionsManager permissionsManager = new PermissionsManager(this);
            permissionsManager.requestLocationPermissions(getActivity());
        }
    }

    private void addDestinationIconSymbolLayer(@NonNull Style loadedMapStyle) {
        loadedMapStyle.addImage(destinationIconInfo.iconId, destinationIconInfo.icon);
        GeoJsonSource geoJsonSource = new GeoJsonSource(destinationIconInfo.geoJsonLayerId);
        loadedMapStyle.addSource(geoJsonSource);
        SymbolLayer destinationSymbolLayer = new SymbolLayer(destinationIconInfo.symbolLayerId, destinationIconInfo.geoJsonLayerId);
        destinationSymbolLayer.withProperties(
                iconImage(destinationIconInfo.iconId),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
        );
        loadedMapStyle.addLayer(destinationSymbolLayer);
    }

    private void addPickupIconSymbolLayer(@NonNull Style loadedMapStyle) {
        loadedMapStyle.addImage(pickupIconInfo.iconId, pickupIconInfo.icon);
        GeoJsonSource geoJsonSource = new GeoJsonSource(pickupIconInfo.geoJsonLayerId);
        loadedMapStyle.addSource(geoJsonSource);
        SymbolLayer destinationSymbolLayer = new SymbolLayer(pickupIconInfo.symbolLayerId, pickupIconInfo.geoJsonLayerId);
        destinationSymbolLayer.withProperties(
                iconImage(pickupIconInfo.iconId),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
        );
        loadedMapStyle.addLayer(destinationSymbolLayer);
    }

    @Override
    public void onResume() {
        super.onResume();
        mMapView.onResume();
    }

    @Override
    public void onStart() {
        super.onStart();
        mMapView.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        mMapView.onStop();
    }

    @Override
    public void onPause() {
        super.onPause();
        mMapView.onPause();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mMapView.onLowMemory();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mMapView.onDestroy();
    }

    @Override
    public void onSaveInstanceState(@NotNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mMapView.onSaveInstanceState(outState);
    }

    @Override
    public void onExplanationNeeded(List<String> permissionsToExplain) {

    }

    @Override
    public void onPermissionResult(boolean granted) {

    }

    /**
     * Called when the user clicks on the map view.
     *
     * @param point The projected map coordinate the user clicked on.
     * @return True if this click should be consumed and not passed further to other listeners registered afterwards,
     * false otherwise.
     */
    @Override
    public boolean onMapClick(@NonNull LatLng point) {
        if (mListener != null)
            mListener.onMapClicked(point);
        Point selectedPoint = Point.fromLngLat(point.getLongitude(), point.getLatitude());
        Point originPoint = Point.fromLngLat(locationComponent.getLastKnownLocation().getLongitude(),
                locationComponent.getLastKnownLocation().getLatitude());
        IconInformation iconInformation;
        iconInformation = selectionMode == LocationMode.START ? pickupIconInfo : destinationIconInfo;
        GeoJsonSource source = mMapboxMap.getStyle().getSourceAs(iconInformation.geoJsonLayerId);
        if (source != null) {
            source.setGeoJson(Feature.fromGeometry(selectedPoint));
        }
        //getRoute(originPoint, destinationPoint);
        return false;
    }

    public void getRoute(Point origin, Point destination) {
        NavigationRoute.builder(getContext())
                .accessToken(Mapbox.getAccessToken())
                .origin(origin)
                .destination(destination)
                .build()
                .getRoute(new Callback<DirectionsResponse>() {
                    @Override
                    public void onResponse(@NotNull Call<DirectionsResponse> call, @NotNull Response<DirectionsResponse> response) {
                        // You can get the generic HTTP info about the response
                        Log.d(TAG, "Response code: " + response.code());
                        if (response.body() == null) {
                            Log.e(TAG, "No routes found, make sure you set the right user and access token.");
                            return;
                        } else if (response.body().routes().size() < 1) {
                            Log.e(TAG, "No routes found");
                            return;
                        }

                        currentRoute = response.body().routes().get(0);

                        // Draw the route on the map
                        if (navigationMapRoute != null) {
                            navigationMapRoute.removeRoute();
                        } else {
                            navigationMapRoute = new NavigationMapRoute(null, mMapView, mMapboxMap, R.style.NavigationMapRoute);
                        }
                        navigationMapRoute.addRoute(currentRoute);
                    }

                    @Override
                    public void onFailure(Call<DirectionsResponse> call, Throwable throwable) {
                        Log.e(TAG, "Error: " + throwable.getMessage());
                    }
                });
    }

    public interface MapInteractionListener {
        void onMapClicked(LatLng clickedPoint);

        void onOnSearchResultAccepted(CarmenFeature searchResult);
    }
}
