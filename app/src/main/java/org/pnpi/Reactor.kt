package org.pnpi

import android.app.Activity
import android.view.View
import android.widget.Switch
import android.widget.TextView
import com.google.android.agera.Merger
import com.google.android.agera.Observable
import com.google.android.agera.Repository
import com.google.android.agera.Updatable

fun <S,T> pairer(): Merger<S, T, Pair<S, T>> = Merger { a,b -> Pair(a,b) }

fun <S,T,U> tripler(): Merger<Pair<S, T>, U, Triple<S, T, U>> = Merger { p,c -> Triple(p.first, p.second, c) }

fun <T> visibilityFollows(parent: Activity, id: Int, repo: Repository<T>, transform: (T) -> Int) =
        Updatable {
            parent.findViewById<View>(id).visibility = transform(repo.get())
        }

fun visibilityFollows(parent: Activity, id: Int, repo: Repository<Int>) =
        visibilityFollows(parent, id, repo, { it })

fun <T> visibilityFollows(parent: View, id: Int, repo: Repository<T>, transform: (T) -> Int) =
        Updatable {
            parent.findViewById<View>(id).visibility = transform(repo.get())
        }

fun visibilityFollows(parent: View, id: Int, repo: Repository<Int>) =
        visibilityFollows(parent, id, repo, { it })

fun <T> textFollows(parent: Activity, id: Int, repo: Repository<T>, transform: (T) -> String) =
        Updatable {
            parent.findViewById<TextView>(id).text = transform(repo.get())
        }

fun textFollows(parent: Activity, id: Int, repo: Repository<String>) =
        textFollows(parent, id, repo, { it })

fun <T> textFollows(parent: View, id: Int, repo: Repository<T>, transform: (T) -> String) =
        Updatable {
            parent.findViewById<TextView>(id).text = transform(repo.get())
        }

fun textFollows(parent: View, id: Int, repo: Repository<String>) =
        textFollows(parent, id, repo, { it })

fun <T> textResidFollows(parent: Activity, id: Int, repo: Repository<T>, transform: (T) -> Int) =
        Updatable {
            parent.findViewById<TextView>(id).setText(transform(repo.get()))
        }

fun textResidFollows(parent: Activity, id: Int, repo: Repository<Int>) =
        textResidFollows(parent, id, repo, { it })

fun <T> isCheckedFollows(parent: View, id: Int, repo: Repository<T>, transform: (T) -> Boolean) =
        Updatable {
            parent.findViewById<Switch>(id).isChecked = transform(repo.get())
        }

fun isCheckedFollows(parent: View, id: Int, repo: Repository<Boolean>) =
        isCheckedFollows(parent, id, repo, { it })

fun <T> isClickableFollows(parent: View, id: Int, repo: Repository<T>, transform: (T) -> Boolean) =
        Updatable {
            parent.findViewById<View>(id).isClickable = transform(repo.get())
        }

fun isClickableFollows(parent: View, id: Int, repo: Repository<Boolean>) =
        isClickableFollows(parent, id, repo, { it })

fun <T> isEnabledFollows(parent: View, id: Int, repo: Repository<T>, transform: (T) -> Boolean) =
        Updatable {
            parent.findViewById<View>(id).isEnabled = transform(repo.get())
        }

fun isEnabledFollows(parent: View, id: Int, repo: Repository<Boolean>) =
        isEnabledFollows(parent, id, repo, { it })

class Reactor() {
    private val links = mutableListOf<Pair<Observable, Updatable>>()
    private var activated = false

    fun link(o: Observable, u: Updatable, update: Boolean = true) {
        links.add(Pair(o,u))
        if (update) {
            u.update()
        }
    }

    fun activate() {
        if (!activated) {
            activated = true
            links.forEach { it.first.addUpdatable(it.second) }
        }
    }

    fun deactivate() {
        if (activated) {
            activated = false
            links.reversed().forEach { it.first.removeUpdatable(it.second) }
        }
    }
}
