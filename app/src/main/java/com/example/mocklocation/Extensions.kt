package com.example.mocklocation

import android.view.View
import android.widget.AdapterView
import android.widget.Spinner

/**
 * Convenience wrapper that invokes [onSelected] with the selected position,
 * ignoring the "nothing selected" callback.
 */
fun Spinner.setOnItemSelectedListenerCompat(onSelected: (Int) -> Unit) {
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            onSelected(position)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}
