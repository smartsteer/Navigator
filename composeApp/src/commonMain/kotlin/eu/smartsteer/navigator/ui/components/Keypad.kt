package eu.smartsteer.navigator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.ui.theme.Ss

/**
 * Klawiatura na ekranie (jak FormNumeric / FormKeyboard w AgOpenGPS).
 *
 * Windows nie pokazuje klawiatury dotykowej dla okien Compose, a Android nie pokazuje systemowej klawiatury, gdy pole
 * dostaje fokus z kodu. Dlatego:
 *  - liczby wpisuje się zawsze na własnym, dużym klawiszu numerycznym (pod palec w rękawicy, na obu systemach),
 *  - tekst na Windows — na własnej klawiaturze; na Androidzie zostaje klawiatura systemowa.
 * Fizyczna klawiatura (laptop) działa w okienku normalnie: Enter = OK, Esc = Anuluj.
 */
enum class KeypadMode { Number, Ip, Text }

class KeypadRequest(
    val title: String,
    val initial: String,
    val mode: KeypadMode,
    val password: Boolean = false,
    val hint: String = "",
    val maxLength: Int = 60,
    val filter: (String) -> String = { it },
    val onDone: (String) -> Unit,
)

/** Jedno okienko klawiatury naraz na całą aplikację. */
object Keypad {
    var request by mutableStateOf<KeypadRequest?>(null)
        private set

    fun open(r: KeypadRequest) { request = r }
    fun close() { request = null }
}

/** true = system sam pokazuje klawiaturę przy dotknięciu pola tekstowego (Android). Windows: false. */
expect val systemTextKeyboard: Boolean

/** Okienko klawiatury — rysowane nad całym ekranem, w App(). */
@Composable
fun KeypadHost() {
    val r = Keypad.request ?: return
    var tf by remember(r) { mutableStateOf(TextFieldValue(r.initial, TextRange(r.initial.length))) }
    var shift by remember(r) { mutableStateOf(false) }
    var showPass by remember(r) { mutableStateOf(false) }
    val focus = remember(r) { FocusRequester() }

    fun set(s: String) {
        val v = r.filter(s).take(r.maxLength)
        tf = TextFieldValue(v, TextRange(v.length))
    }
    fun type(s: String) = set(tf.text + s)
    fun back() { if (tf.text.isNotEmpty()) set(tf.text.dropLast(1)) }
    fun ok() { Keypad.close(); r.onDone(tf.text) }
    fun cancel() = Keypad.close()

    // tło przyciemnia ekran i łapie dotknięcia (dotknięcie obok = anuluj)
    Box(
        Modifier.fillMaxSize().background(Color(0xCC050807))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { cancel() },
        contentAlignment = Alignment.Center,
    ) {
        val narrow = r.mode != KeypadMode.Text
        Column(
            Modifier.widthIn(max = if (narrow) 440.dp else 960.dp).fillMaxWidth().padding(16.dp)
                .clip(RoundedCornerShape(18.dp)).background(Ss.Surface).border(1.dp, Ss.BorderStrong, RoundedCornerShape(18.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(r.title, color = Ss.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.hint.isNotEmpty()) Text(r.hint, color = Ss.TextMuted, fontSize = 13.sp)

            // pole z wpisywanym tekstem; fokus — żeby działała też fizyczna klawiatura
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = tf,
                    onValueChange = { v -> val f = r.filter(v.text).take(r.maxLength); tf = if (f == v.text) v else TextFieldValue(f, TextRange(f.length)) },
                    singleLine = true,
                    readOnly = systemTextKeyboard,          // Android: bez klawiatury systemowej nad naszą
                    textStyle = TextStyle(
                        color = Ss.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        textAlign = if (narrow) TextAlign.End else TextAlign.Start,
                    ),
                    cursorBrush = SolidColor(Ss.Accent),
                    visualTransformation = if (r.password && !showPass) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (narrow) KeyboardType.Decimal else KeyboardType.Text),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Ss.Ground)
                        .border(2.dp, Ss.Accent, RoundedCornerShape(12.dp))
                        .focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) false
                            else when (e.key) {
                                Key.Enter, Key.NumPadEnter -> { ok(); true }
                                Key.Escape -> { cancel(); true }
                                else -> false
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                )
                if (r.password) Key(if (showPass) "Ukryj" else "Pokaż", weight = 0.25f, size = 15.sp) { showPass = !showPass }
            }

            if (narrow) {
                val ip = r.mode == KeypadMode.Ip
                KeyRow { Key("7") { type("7") }; Key("8") { type("8") }; Key("9") { type("9") }; Key("⌫", accent = Ss.Warning) { back() } }
                KeyRow { Key("4") { type("4") }; Key("5") { type("5") }; Key("6") { type("6") }; Key("C", accent = Ss.Warning) { set("") } }
                KeyRow {
                    Key("1") { type("1") }; Key("2") { type("2") }; Key("3") { type("3") }
                    if (ip) Key(".") { type(".") } else Key("±") { set(if (tf.text.startsWith("-")) tf.text.drop(1) else "-" + tf.text) }
                }
                KeyRow {
                    Key("0", weight = 2f) { type("0") }
                    if (ip) Key(" ", enabled = false) { } else Key(",") { if (!tf.text.contains(',') && !tf.text.contains('.')) type(",") }
                    Key("OK", background = Ss.Accent, color = Ss.OnAccent) { ok() }
                }
                KeyRow { Key("Anuluj", size = 16.sp) { cancel() } }
            } else {
                fun l(c: String) = if (shift) c.uppercase() else c
                KeyRow { "1234567890".forEach { c -> Key(c.toString()) { type(c.toString()) } } }
                KeyRow { "qwertyuiop".forEach { c -> val k = l(c.toString()); Key(k) { type(k) } } }
                KeyRow { Spacer(0.5f); "asdfghjkl".forEach { c -> val k = l(c.toString()); Key(k) { type(k) } }; Spacer(0.5f) }
                KeyRow {
                    Key("⇧", weight = 1.5f, background = if (shift) Ss.AccentSoft else Ss.SurfaceHigh, accent = if (shift) Ss.Accent else null) { shift = !shift }
                    "zxcvbnm".forEach { c -> val k = l(c.toString()); Key(k) { type(k) } }
                    Key("⌫", weight = 1.5f, accent = Ss.Warning) { back() }
                }
                KeyRow { "ąćęłńóśźż".forEach { c -> val k = l(c.toString()); Key(k) { type(k) } } }
                KeyRow {
                    listOf(".", "-", "_", "/", ":", "@").forEach { c -> Key(c) { type(c) } }
                    Key("spacja", weight = 4f, size = 15.sp) { type(" ") }
                }
                KeyRow {
                    Key("Anuluj", size = 16.sp) { cancel() }
                    Key("Wyczyść", size = 16.sp, accent = Ss.Warning) { set("") }
                    Key("OK", weight = 2f, background = Ss.Accent, color = Ss.OnAccent) { ok() }
                }
            }
        }
    }
    LaunchedEffect(r) { runCatching { focus.requestFocus() } }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)

@Composable
private fun RowScope.Spacer(weight: Float) = Box(Modifier.weight(weight))

@Composable
private fun RowScope.Key(
    text: String,
    weight: Float = 1f,
    size: TextUnit = 22.sp,
    background: Color = Ss.SurfaceHigh,
    color: Color = Ss.Text,
    accent: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier.weight(weight).height(56.dp)
            .clip(RoundedCornerShape(10.dp)).background(if (enabled) background else Color.Transparent)
            .border(1.dp, if (enabled) (accent ?: Ss.BorderStrong) else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = accent ?: color, fontSize = size, fontWeight = FontWeight.Bold) }
}

/**
 * Pole tekstowe: na Androidzie zwykłe (klawiatura systemowa), na Windows — dotknięcie otwiera klawiaturę na ekranie.
 * [title] to nagłówek okienka klawiatury.
 */
@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    hint: String = "",
    password: Boolean = false,
    mode: KeypadMode = KeypadMode.Text,
    maxLength: Int = 60,
    fontSize: TextUnit = 17.sp,
    textAlign: TextAlign = TextAlign.Start,
    filter: (String) -> String = { it },
) {
    val shape = RoundedCornerShape(10.dp)
    val base = modifier.clip(shape).background(Ss.Ground).border(1.dp, Ss.BorderStrong, shape)
    if (systemTextKeyboard && mode == KeypadMode.Text) {
        BasicTextField(
            value = value,
            onValueChange = { onChange(filter(it).take(maxLength)) },
            singleLine = true,
            textStyle = TextStyle(color = Ss.Text, fontSize = fontSize, textAlign = textAlign),
            cursorBrush = SolidColor(Ss.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
            modifier = base.padding(horizontal = 12.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = Ss.TextMuted, fontSize = fontSize)
                inner()
            },
        )
    } else {
        val shown = if (password) "•".repeat(value.length) else value
        Box(
            base.clickable(role = Role.Button) {
                Keypad.open(KeypadRequest(title, value, mode, password, hint, maxLength, filter) { onChange(filter(it).take(maxLength)) })
            }.padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Text(
                shown.ifEmpty { hint }, color = if (value.isEmpty()) Ss.TextMuted else Ss.Text, fontSize = fontSize,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
