package ru.recipe.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

data class Route(val kind: String, val id: Long=0, val groupId: Long=0, val parentId: Long=0)

class AppState(app: Application): AndroidViewModel(app) {
    val store=SessionStore(app)
    val api=Api(store)
    var user by mutableStateOf(store.user())
    val stack=mutableStateListOf(Route("catalog"))
    val route get()=stack.last()
    var data by mutableStateOf<JSONObject?>(null)
    var entries by mutableStateOf<List<JSONObject>>(emptyList())
    var loading by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<ApiFailure?>(null)
    var notice by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var category by mutableStateOf("")
    var maxMinutes by mutableStateOf(0)
    var page by mutableIntStateOf(1)
    var hasMore by mutableStateOf(false)
    var total by mutableIntStateOf(0)
    var draft by mutableStateOf<RecipeDraft?>(null)
    var requestRevision by mutableIntStateOf(0)
    var transientDirty by mutableStateOf(false)
    private var generation=0
    private var retryAction: (suspend ()->Unit)?=null

    init { if(user!=null) task { refreshUser() } }
    fun go(next: Route, root: Boolean=false) {
        if(busy) return
        generation++;error=null;data=null;entries=emptyList();transientDirty=false
        if(root) stack.clear()
        stack.add(next);requestRevision++
    }
    fun back() {
        if(busy) return
        generation++;error=null;data=null;entries=emptyList();transientDirty=false
        if(stack.size>1) stack.removeAt(stack.lastIndex) else { stack.clear();stack.add(Route("catalog")) }
        requestRevision++
    }
    fun requireLogin(action: ()->Unit) { if(user==null) go(Route("login")) else action() }
    fun complete(next: Route, root: Boolean=false) { busy=false;go(next,root) }
    fun completeBack() { busy=false;back() }
    fun retry() { val action=retryAction;if(action!=null)task(action) else reload() }
    fun task(action: suspend ()->Unit) {
        if(busy) return
        busy=true;error=null
        viewModelScope.launch {
            try { action();retryAction=null }
            catch(e: CancellationException) { throw e }
            catch(e: ApiFailure) { retryAction=action;handle(e) }
            catch(e: Exception) { error=ApiFailure(0,"unexpected","Не удалось выполнить действие. Повторите.") }
            finally { busy=false }
        }
    }
    private fun handle(e: ApiFailure) {
        error=e
        if(e.status==401 && user!=null) {
            store.clear();user=null;draft=null;data=null;entries=emptyList()
            stack.clear();stack.add(Route("login"));requestRevision++
        }
        if(e.status in listOf(403,404) && route.kind in listOf("group","members","dish","attempt")) {
            data=null;entries=emptyList()
        }
    }
    suspend fun refreshUser() {
        val u=JSONObject(api.call("/me"));user=u;store.saveUser(u)
    }
    fun authenticated(result: JSONObject) {
        val u=result.getJSONObject("user");store.save(result.getString("token"),u);user=u
        generation++;stack.clear();stack.add(Route("catalog"));data=null;entries=emptyList();requestRevision++
        notice="Вы вошли в аккаунт"
    }
    fun logout()=task {
        api.call("/auth/logout","POST")
        store.clear();user=null;draft=null;data=null;entries=emptyList();generation++
        stack.clear();stack.add(Route("catalog"));requestRevision++;notice="Вы вышли из аккаунта"
    }
    fun reload(append: Boolean=false) {
        retryAction=null
        val target=route;val g=++generation
        loading=true;error=null
        viewModelScope.launch {
            try {
                if(target.kind in listOf("catalog","favorites","mine")) {
                    if(!append) page=1
                    val enc=URLEncoder.encode(if(target.kind=="catalog")query.trim() else "","UTF-8")
                    val path="/recipes?q=$enc&page=$page"+
                        (if(target.kind=="catalog"&&category.isNotEmpty()) "&category="+URLEncoder.encode(category,"UTF-8") else "")+
                        (if(target.kind=="catalog"&&maxMinutes>0) "&max_minutes=$maxMinutes" else "")+
                        (if(target.kind=="favorites") "&favorites=true" else if(target.kind=="mine") "&mine=true" else "")
                    val obj=JSONObject(api.call(path))
                    if(g==generation) { data=obj;entries=if(append) entries+obj.objects("items") else obj.objects("items");hasMore=obj.optBoolean("has_more");total=obj.optInt("total") }
                } else {
                    val path=when(target.kind) {
                        "recipe","reviews"->"/recipes/"+target.id
                        "groups"->"/groups"
                        "group","members"->"/groups/"+target.id
                        "dish"->"/dishes/"+target.id
                        "attempt"->"/attempts/"+target.id
                        "invitations"->"/invitations"
                        "profile","subscription"->"/me"
                        else->null
                    }
                    if(path!=null && !(target.kind=="profile"&&user==null)) {
                        val raw=api.call(path)
                        val obj=if(target.kind in listOf("groups","invitations")) json("items" to JSONArray(raw)) else JSONObject(raw)
                        if(target.kind=="reviews") obj.put("reviews",JSONArray(api.call("/recipes/"+target.id+"/reviews")))
                        if(g==generation) {
                            data=obj;entries=obj.objects("items")
                            if(target.kind in listOf("profile","subscription")) { user=obj;store.saveUser(obj) }
                        }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: ApiFailure) { if(g==generation)handle(e) }
            catch(e: Exception) { if(g==generation)error=ApiFailure(0,"unexpected","Не удалось загрузить данные. Повторите.") }
            finally { if(g==generation)loading=false }
        }
    }
    fun refresh() { requestRevision++ }
    fun openForm(recipe: JSONObject?=null, dishId: Long=0, groupId: Long=0, parentId: Long=0) {
        draft=RecipeDraft(recipe,dishId,groupId,parentId)
        go(Route("form"))
    }
    fun resetFilters() { query="";category="";maxMinutes=0;refresh() }
}

class IngredientDraft(name: String="",amount: String="",unit: String="г") {
    var name by mutableStateOf(name)
    var amount by mutableStateOf(amount)
    var unit by mutableStateOf(unit)
}

class RecipeDraft(source: JSONObject?=null,val dishId: Long=0,val groupId: Long=0,val parentId: Long=0) {
    val recipeId=if(dishId==0L)source?.optLong("id") ?: 0 else 0
    var title by mutableStateOf(source?.text("title") ?: "")
    var description by mutableStateOf(source?.text("description") ?: "")
    var category by mutableStateOf(source?.text("category") ?: "Завтраки")
    var minutes by mutableStateOf(source?.optInt("minutes")?.toString() ?: "")
    var servings by mutableStateOf(source?.optInt("servings")?.toString() ?: "1")
    var imageId by mutableStateOf(source?.text("image_id") ?: "")
    var photoBytes by mutableStateOf<ByteArray?>(null)
    var photoMime by mutableStateOf("image/jpeg")
    var note by mutableStateOf("")
    val ingredients=mutableStateListOf<IngredientDraft>().apply {
        source?.objects("ingredients")?.forEach { add(IngredientDraft(it.text("name"),it.optDouble("amount").let { a->if(a%1==0.0)a.toInt().toString() else a.toString() },it.text("unit"))) }
        if(isEmpty()) add(IngredientDraft())
    }
    val steps=mutableStateListOf<String>().apply { addAll(source?.strings("steps") ?: listOf(""));if(isEmpty())add("") }
    var key=UUID.randomUUID().toString()
    var fingerprint=""
    val initial=signature()
    fun signature()=listOf(title,description,category,minutes,servings,imageId,note,ingredients.joinToString { it.name+it.amount+it.unit },steps.joinToString()).joinToString("|")+photoBytes?.contentHashCode()
    val dirty get()=signature()!=initial
    fun validate(): Map<String,String> {
        val errors=mutableMapOf<String,String>()
        if(title.trim().length !in 3..100)errors["title"]="Название: от 3 до 100 символов"
        if(description.trim().length>1000)errors["description"]="Описание: не более 1000 символов"
        if(minutes.toIntOrNull() !in 1..1440)errors["minutes"]="Укажите от 1 до 1440 минут"
        if(servings.toIntOrNull() !in 1..100)errors["servings"]="Укажите от 1 до 100 порций"
        if(dishId>0 && note.trim().length !in 1..2000)errors["note"]="Результат: от 1 до 2000 символов"
        ingredients.forEachIndexed { i,v->
            if(v.name.trim().length !in 1..100)errors["ingredients.$i.name"]="Название: от 1 до 100 символов"
            val n=v.amount.replace(',','.').toDoubleOrNull()
            if(n==null||!n.isFinite()||n<=0||n>10000)errors["ingredients.$i.amount"]="Количество должно быть больше 0 и не более 10 000"
        }
        steps.forEachIndexed { i,s->if(s.trim().length !in 1..1000)errors["steps.$i"]="Шаг: от 1 до 1000 символов" }
        return errors
    }
    fun payload(): JSONObject = json(
        "title" to title.trim(),"description" to description.trim(),"category" to category,
        "minutes" to minutes.toInt(),"servings" to servings.toInt(),
        "ingredients" to JSONArray(ingredients.map { json("name" to it.name.trim(),"amount" to it.amount.replace(',','.').toDouble(),"unit" to it.unit) }),
        "steps" to JSONArray(steps.map { it.trim() }),"image_id" to imageId.ifEmpty { null }
    ).apply { if(dishId>0) { put("note",note.trim());put("parent_id",if(parentId>0)parentId else JSONObject.NULL) } }
    fun operationKey(body: JSONObject): String {
        val now=body.toString()
        if(fingerprint.isNotEmpty()&&fingerprint!=now)key=UUID.randomUUID().toString()
        fingerprint=now
        return key
    }
}
