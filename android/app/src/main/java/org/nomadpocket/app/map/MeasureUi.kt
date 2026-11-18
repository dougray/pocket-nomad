// MeasureUi.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// The measure/draw panel over the map, the saved-drawings list, and the map
// layers for drawings. Port of MeasureViews.swift.

package org.nomadpocket.app.map

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.maps.Style
import org.nomadpocket.mesh.Mesh
import org.nomadpocket.mesh.MeshConversation
import java.io.File
import java.util.Locale

const val SRC_SHAPES = "shapes"
const val SRC_SHAPE_LABELS = "shape-labels"
const val SRC_SHAPE_DRAFT = "shape-draft"

private fun hex(i: Int) = String.format(Locale.US, "#%06X", ShapeMath.PALETTE[i % ShapeMath.PALETTE.size].second)
private fun colorOf(i: Int) = Color(0xFF000000 or ShapeMath.PALETTE[i % ShapeMath.PALETTE.size].second.toLong())

private fun ring(points: List<ShapePoint>): List<Point> = points.map { Point.fromLngLat(it.longitude, it.latitude) }

fun shapeFeatures(shapes: List<MapShape>): FeatureCollection = FeatureCollection.fromFeatures(shapes.filter { it.points.size >= 2 }.map { s ->
    val f = if (s.isArea) Feature.fromGeometry(Polygon.fromLngLats(listOf(ring(s.points) + Point.fromLngLat(s.points[0].longitude, s.points[0].latitude))))
            else Feature.fromGeometry(LineString.fromLngLats(ring(s.points)))
    f.addStringProperty("color", hex(s.colorIndex))
    f
})

fun shapeLabelFeatures(shapes: List<MapShape>): FeatureCollection = FeatureCollection.fromFeatures(shapes.filter { it.points.size >= 2 }.map { s ->
    val c = ShapeMath.centre(s.points)
    Feature.fromGeometry(Point.fromLngLat(c.longitude, c.latitude)).apply { addStringProperty("name", s.name); addStringProperty("color", hex(s.colorIndex)) }
})

fun draftFeatures(draft: List<LatLng>, closed: Boolean): FeatureCollection {
    val pts = draft.map { Point.fromLngLat(it.longitude, it.latitude) }
    val out = ArrayList<Feature>()
    if (pts.size >= 2) out.add(Feature.fromGeometry(if (closed && pts.size >= 3) Polygon.fromLngLats(listOf(pts + pts[0])) else LineString.fromLngLats(pts)))
    pts.forEach { out.add(Feature.fromGeometry(it)) }
    return FeatureCollection.fromFeatures(out)
}

/** Adds the drawings' sources and layers (above the coverage, below pins). */
fun addShapeLayers(style: Style, labelFont: Array<String>, below: String? = null) {
    listOf(SRC_SHAPES, SRC_SHAPE_LABELS, SRC_SHAPE_DRAFT).forEach { style.addSource(GeoJsonSource(it, FeatureCollection.fromFeatures(emptyList()))) }
    val c = Expression.toColor(get("color"))
    val layers = listOf(
        FillLayer("shape-fills", SRC_SHAPES).withProperties(PropertyFactory.fillColor(c), PropertyFactory.fillOpacity(0.25f)),
        LineLayer("shape-lines", SRC_SHAPES).withProperties(PropertyFactory.lineColor(c), PropertyFactory.lineWidth(3f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)),
        SymbolLayer("shape-names", SRC_SHAPE_LABELS).withProperties(PropertyFactory.textField(get("name")), PropertyFactory.textFont(labelFont),
            PropertyFactory.textSize(12f), PropertyFactory.textColor(c), PropertyFactory.textHaloColor(android.graphics.Color.BLACK),
            PropertyFactory.textHaloWidth(1.5f), PropertyFactory.textOptional(true)),
        FillLayer("draft-fill", SRC_SHAPE_DRAFT).withFilter(eq(Expression.geometryType(), literal("Polygon"))).withProperties(PropertyFactory.fillColor(android.graphics.Color.rgb(255, 214, 10)), PropertyFactory.fillOpacity(0.2f)),
        LineLayer("draft-line", SRC_SHAPE_DRAFT).withProperties(PropertyFactory.lineColor(android.graphics.Color.rgb(255, 214, 10)),
            PropertyFactory.lineWidth(3f), PropertyFactory.lineDasharray(arrayOf(2f, 1.5f))),
        CircleLayer("draft-points", SRC_SHAPE_DRAFT).withFilter(eq(Expression.geometryType(), literal("Point"))).withProperties(
            PropertyFactory.circleRadius(6f), PropertyFactory.circleColor(android.graphics.Color.rgb(255, 214, 10)),
            PropertyFactory.circleStrokeColor(android.graphics.Color.BLACK), PropertyFactory.circleStrokeWidth(2f)),
    )
    layers.forEach { if (below != null && style.getLayer(below) != null) style.addLayerBelow(it, below) else style.addLayer(it) }
}

@Composable
fun MeasurePanel(
    draft: List<LatLng>, closed: Boolean, metric: Boolean,
    onUndo: () -> Unit, onClear: () -> Unit, onClosed: (Boolean) -> Unit, onSave: () -> Unit, onDone: () -> Unit, onList: () -> Unit,
) {
    val pts = draft.map { ShapePoint(it.latitude, it.longitude) }
    val isArea = closed && pts.size >= 3
    Panel {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pts.isEmpty()) {
                Text("Tap the map to add points. Measure a route or close it to measure an area.", fontSize = 14.sp)
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ShapeMath.distanceText(ShapeMath.length(pts, isArea), metric), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(if (isArea) "around" else "total", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isArea) Text(ShapeMath.areaText(ShapeMath.area(pts), metric), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                if (pts.size >= 2) {
                    val a = pts[pts.size - 2]; val b = pts.last()
                    Text("Last leg ${ShapeMath.distanceText(KnownRadioParser.distance(a.latitude, a.longitude, b.latitude, b.longitude), metric)}, heading ${ShapeMath.bearingText(ShapeMath.bearing(a, b))}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onUndo, enabled = draft.isNotEmpty()) { Text("Undo") }
                TextButton(onClick = onClear, enabled = draft.isNotEmpty()) { Text("Clear") }
                FilterChip(selected = closed, onClick = { onClosed(!closed) }, enabled = draft.size >= 3, label = { Text("Area") })
                Row(Modifier.weight(1f)) {}
                Button(onClick = onSave, enabled = draft.size >= 2) { Text("Save") }
                TextButton(onClick = onDone) { Text("Done") }
            }
            TextButton(onClick = onList) { Text("My drawings…", fontSize = 12.sp) }
        }
    }
}

@Composable
fun SaveShapeDialog(isArea: Boolean, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel, title = { Text("Save drawing") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Text(if (isArea) "Saved as an area." else "Saved as a line.", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Writes the file into the shareable cache folder and opens the share sheet. */
private fun shareFile(context: Context, name: String, text: String, ext: String, mime: String) {
    val dir = File(context.cacheDir, "drawings-export").also { it.mkdirs() }
    val file = File(dir, name.replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "drawing" } + "." + ext)
    file.writeText(text)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.mesh.files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share drawing").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun ShapesDialog(
    shapes: List<MapShape>, metric: Boolean, context: Context,
    onDismiss: () -> Unit, onShow: (MapShape) -> Unit, onRename: (MapShape, String) -> Unit, onRecolor: (MapShape) -> Unit, onDelete: (MapShape) -> Unit,
) {
    var renaming by remember { mutableStateOf<MapShape?>(null) }
    var newName by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf<MapShape?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My drawings") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (shapes.isEmpty()) item { Text("Nothing drawn yet. Tap the ruler on the map, tap some points and save.") }
                items(shapes, key = { it.id }) { s ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.size(12.dp).background(colorOf(s.colorIndex), CircleShape)) {}
                            Text(s.name, Modifier.padding(start = 8.dp).clickable { onShow(s) }, fontWeight = FontWeight.Bold)
                        }
                        Text(if (s.isArea) "${ShapeMath.areaText(ShapeMath.area(s.points), metric)} · ${ShapeMath.distanceText(ShapeMath.length(s.points, true), metric)} around · ${s.points.size} points"
                             else "${ShapeMath.distanceText(ShapeMath.length(s.points, false), metric)} · ${s.points.size} points", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            TextButton(onClick = { renaming = s; newName = s.name }) { Text("Rename", fontSize = 12.sp) }
                            TextButton(onClick = { onRecolor(s) }) { Text("Colour", fontSize = 12.sp) }
                            TextButton(onClick = { shareFile(context, s.name, ShapeMath.geoJson(listOf(s)), "geojson", "application/geo+json") }) { Text("GeoJSON", fontSize = 12.sp) }
                            TextButton(onClick = { shareFile(context, s.name, ShapeMath.gpx(listOf(s)), "gpx", "application/gpx+xml") }) { Text("GPX", fontSize = 12.sp) }
                        }
                        Row {
                            TextButton(onClick = { sending = s }) { Text("Send to mesh", fontSize = 12.sp) }
                            TextButton(onClick = { onDelete(s) }) { Text("Delete", fontSize = 12.sp, color = Color(0xFFE5484D)) }
                        }
                    }
                    HorizontalDivider()
                }
                status?.let { item { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (shapes.isNotEmpty()) ({ TextButton(onClick = { shareFile(context, "Pocket Nomad drawings", ShapeMath.geoJson(shapes), "geojson", "application/geo+json") }) { Text("Share all") } }) else null,
    )
    renaming?.let { r ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename") },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(r, newName.trim().ifEmpty { r.name }); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    sending?.let { s ->
        AlertDialog(onDismissRequest = { sending = null }, title = { Text("Send to the public mesh channel?") },
            text = { Text("Everyone on the default channel can read a name, size and centre point.") },
            confirmButton = {
                TextButton(onClick = {
                    val svc = Mesh.service
                    val text = ShapeMath.meshSummary(s, metric, svc.maxTextBytes)
                    status = if (svc.send(text, MeshConversation.Channel(0))) "Sent: $text" else "Couldn't send: connect a radio first."
                    sending = null
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { sending = null }) { Text("Cancel") } })
    }
}
