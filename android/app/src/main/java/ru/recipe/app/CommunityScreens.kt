package ru.recipe.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.UUID

class CreationKey {
    private var fingerprint=""
    private var key=UUID.randomUUID().toString()
    fun forBody(body: JSONObject): String {
        val value=body.toString()
        if(fingerprint.isNotEmpty()&&fingerprint!=value)key=UUID.randomUUID().toString()
        fingerprint=value
        return key
    }
}
@Composable fun ReviewsScreen(vm:AppState) {
    val r=vm.data ?: return
    val rid=r.getLong("id")
    val own=r.objects("reviews").firstOrNull { it.optLong("author_id")==vm.user?.optLong("id") }
    var text by rememberSaveable(rid,own?.text("text")) { mutableStateOf(own?.text("text") ?: "") }
    var localError by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf(false) }
    val key=remember(rid) { CreationKey() }
    val author=vm.user?.optLong("id")==r.optLong("author_id")
    LaunchedEffect(text,own?.text("text")) { vm.transientDirty=text!=(own?.text("text") ?: "") }
    ContentList {
        item { Text(r.text("title"),fontSize=18.sp);Text(ratingText(r)+" · "+r.optInt("rating_count")+" оценок",color=Muted,fontSize=13.sp) }
        if(author)item { InfoCard("Это ваш рецепт",body="Автор не оценивает свой рецепт и не пишет к нему отзывы.") }
        else {
            item {
                Text("Ваша оценка",color=Muted,fontSize=14.sp)
                Row {
                    for(star in 1..5)IconButton(onClick={vm.requireLogin { vm.task { vm.api.call("/recipes/$rid/rating","PUT",json("value" to star));vm.refresh() } }},enabled=!vm.busy) {
                        Icon(if(star<=r.optInt("my_rating"))Icons.Default.Star else Icons.Default.StarBorder,"Оценить на $star",tint=Green)
                    }
                }
                Text("Оценка и отзыв сохраняются независимо.",fontSize=12.sp,color=Muted)
            }
            if(vm.user!=null) {
                item { Field(text,if(own==null)"Ваш отзыв" else "Ваш отзыв · редактирование",{text=it},localError ?: vm.error?.fields?.get("text"),!vm.busy,multiline=true) }
                item {
                    PrimaryButton("Сохранить отзыв",!vm.busy) {
                        localError=if(text.trim().length !in 1..2000)"Отзыв: от 1 до 2000 символов" else null
                        if(localError==null)vm.task {
                            val body=json("text" to text.trim())
                            vm.api.call(if(own==null)"/recipes/$rid/reviews" else "/reviews/"+own.getLong("id"),if(own==null)"POST" else "PUT",body,if(own==null)key.forBody(body) else null)
                            vm.notice="Отзыв сохранён";vm.refresh()
                        }
                    }
                    if(own!=null)TextButton(onClick={delete=true},enabled=!vm.busy){Text("Удалить свой отзыв")}
                }
            } else item { PrimaryButton("Войти, чтобы написать отзыв"){vm.go(Route("login"))} }
        }
        if(r.objects("reviews").isEmpty())item { EmptyState("Отзывов пока нет","Поделитесь первым впечатлением о рецепте.") }
        items(r.objects("reviews"),key={it.getLong("id")}) { review->
            InfoCard(review.text("author_name"),dateText(review.text("created_at")),review.text("text")) {
                Row {
                    listOf(1 to Icons.Default.ThumbUp,-1 to Icons.Default.ThumbDown).forEach { (value,icon)->
                        TextButton(onClick={vm.requireLogin { vm.task { vm.api.call("/reviews/"+review.getLong("id")+"/reaction","POST",json("value" to value));vm.refresh() } }},
                            enabled=!vm.busy&&review.optLong("author_id")!=vm.user?.optLong("id")) {
                            Icon(icon,if(value==1)"Лайк" else "Дизлайк",Modifier.size(18.dp),
                                tint=if(review.optInt("my_reaction")==value)Green else Muted)
                            Spacer(Modifier.width(8.dp));Text(review.optInt(if(value==1)"likes" else "dislikes").toString())
                        }
                    }
                }
            }
        }
    }
    if(delete)ConfirmDialog("Удалить отзыв?","Реакции на отзыв также будут удалены. Оценка рецепта сохранится.",{
        delete=false;vm.task { vm.api.call("/reviews/"+own!!.getLong("id"),"DELETE");text="";vm.notice="Отзыв удалён";vm.refresh() }
    },{delete=false},"Удалить")
}
@Composable fun NameDialog(vm:AppState,title:String,withDescription:Boolean=false,dismiss:()->Unit,save:(String,String,CreationKey)->Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var cancel by remember { mutableStateOf(false) }
    val key=remember { CreationKey() }
    val leave={if(name.isNotBlank()||description.isNotBlank())cancel=true else dismiss()}
    AlertDialog(onDismissRequest={if(!vm.busy)leave()},title={Text(title)},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Field(name,"Название",{name=it},error,!vm.busy)
            if(withDescription)Field(description,"Описание",{description=it},enabled=!vm.busy,multiline=true)
            vm.error?.let { Text(it.message,color=MaterialTheme.colorScheme.error,fontSize=13.sp) }
        }
    },confirmButton={TextButton(onClick={
        error=if(name.trim().length !in 3..100)"Название: от 3 до 100 символов" else if(description.trim().length>1000)"Описание: не более 1000 символов" else null
        if(error==null)save(name.trim(),description.trim(),key)
    },enabled=!vm.busy){Text("Создать")}},dismissButton={TextButton(onClick=leave,enabled=!vm.busy){Text("Отмена")}})
    if(cancel)ConfirmDialog("Отменить заполнение?","Введённые данные будут потеряны.",{cancel=false;dismiss()},{cancel=false},"Отменить заполнение")
}
@Composable fun GroupsScreen(vm:AppState) {
    var create by remember { mutableStateOf(false) }
    ContentList {
        item {
            PrimaryButton("+ Создать группу",!vm.busy) {
                if(vm.user?.optBoolean("subscription_active")==true)create=true else vm.go(Route("subscription"))
            }
            TextButton(onClick={vm.go(Route("invitations"))},enabled=!vm.busy){Text("Входящие приглашения")}
        }
        if(vm.entries.isEmpty()&&!vm.loading&&vm.error==null)item { EmptyState("У вас пока нет групп","Создайте группу или примите приглашение от друзей.") }
        items(vm.entries,key={it.getLong("id")}) { g->
            InfoCard(g.text("title"),g.optInt("member_count").toString()+" участников · "+g.optInt("dish_count")+" блюд",
                if(g.optBoolean("writable"))"Подписка создателя активна" else "Только просмотр · подписка закончилась",
                onClick={vm.go(Route("group",g.getLong("id")))})
        }
    }
    if(create)NameDialog(vm,"Новая группа",true,{create=false}) { name,desc,key->
        vm.task {
            val body=json("title" to name,"description" to desc)
            val gid=JSONObject(vm.api.call("/groups","POST",body,key.forBody(body))).getLong("id")
            create=false;vm.notice="Группа создана";vm.complete(Route("group",gid))
        }
    }
}
@Composable fun GroupScreen(vm:AppState) {
    val g=vm.data ?: return
    val gid=g.getLong("id")
    var create by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    ContentList {
        item {
            Text("Приватная группа · создатель: "+g.text("owner_name"),fontSize=13.sp,color=Muted)
            if(g.text("description").isNotEmpty())Text(g.text("description"),fontSize=14.sp)
            OutlinedButton(onClick={vm.go(Route("members",gid))},enabled=!vm.busy){Text("Участники и приглашения")}
        }
        if(!g.optBoolean("writable"))item { EmptyState("Доступен только просмотр","Подписка создателя закончилась. Сохранённые материалы доступны для просмотра.") }
        if(g.objects("dishes").isEmpty())item { EmptyState("В группе пока нет блюд") }
        items(g.objects("dishes"),key={it.getLong("id")}) { d->
            InfoCard(d.text("title"),d.optInt("attempt_count").toString()+" попыток",onClick={vm.go(Route("dish",d.getLong("id"),gid))})
        }
        item { PrimaryButton("+ Новая разработка блюда",!vm.busy&&g.optBoolean("writable")){create=true} }
        if(g.optLong("owner_id")!=vm.user?.optLong("id"))item { TextButton(onClick={leave=true},enabled=!vm.busy){Text("Выйти из группы")} }
    }
    if(create)NameDialog(vm,"Новая разработка блюда",dismiss={create=false}) { name,_,key->
        vm.task {
            val body=json("title" to name)
            val id=JSONObject(vm.api.call("/groups/$gid/dishes","POST",body,key.forBody(body))).getLong("id")
            create=false;vm.complete(Route("dish",id,gid));vm.notice="Блюдо создано"
        }
    }
    if(leave)ConfirmDialog("Выйти из группы?","Ваши попытки останутся в истории. Для возвращения потребуется новое приглашение.",{
        leave=false;vm.task { vm.api.call("/groups/$gid/leave","POST");vm.complete(Route("groups"),true);vm.notice="Вы вышли из группы" }
    },{leave=false},"Выйти")
}
@Composable fun MembersScreen(vm:AppState) {
    val g=vm.data ?: return
    val gid=g.getLong("id")
    val owner=g.optLong("owner_id")==vm.user?.optLong("id")
    var login by rememberSaveable { mutableStateOf("") }
    var remove by remember { mutableStateOf<JSONObject?>(null) }
    ContentList {
        items(g.objects("members"),key={it.getLong("id")}) { u->
            InfoCard(u.text("name")+(if(u.optLong("id")==g.optLong("owner_id"))" · создатель" else " · участник"),"@"+u.text("login")) {
                if(owner&&u.optLong("id")!=g.optLong("owner_id"))TextButton(onClick={remove=u},enabled=!vm.busy){Text("Исключить из группы")}
            }
        }
        if(owner) {
            item { Field(login,"Пригласить по логину",{login=it},vm.error?.fields?.get("login"),!vm.busy) }
            item { PrimaryButton("Отправить приглашение",!vm.busy&&login.trim().length>=3) {
                vm.task { vm.api.call("/groups/$gid/invitations","POST",json("login" to login.trim()));login="";vm.notice="Приглашение отправлено";vm.refresh() }
            } }
            items(g.objects("invitations")) { inv->
                Text("@"+inv.text("login")+" · "+when(inv.text("status")){"pending"->"ожидает ответа";"accepted"->"принято";else->"отклонено"},fontSize=13.sp,color=Muted)
            }
        } else item { Text("Управлять участниками может только создатель группы.",fontSize=13.sp,color=Muted) }
    }
    remove?.let { u->ConfirmDialog("Исключить участника?",u.text("name")+" потеряет доступ к группе. Его прошлые записи сохранятся.",{
        remove=null;vm.task { vm.api.call("/groups/$gid/members/"+u.getLong("id"),"DELETE");vm.notice="Участник исключён";vm.refresh() }
    },{remove=null},"Исключить") }
}
@Composable fun DishScreen(vm:AppState) {
    val d=vm.data ?: return
    ContentList {
        item { Text(d.text("title"),fontSize=22.sp) }
        if(!d.optBoolean("writable"))item { EmptyState("Доступен только просмотр","Для новых попыток нужна активная подписка создателя.") }
        if(d.objects("attempts").isEmpty())item { EmptyState("Добавьте первую попытку") }
        items(d.objects("attempts"),key={it.getLong("id")}) { a->
            InfoCard("Попытка №"+a.optInt("number")+(if(!a.isNull("parent_id"))" · на основе предыдущей" else ""),
                a.text("author_name")+" · "+dateText(a.text("created_at")),a.text("note"),
                onClick={vm.go(Route("attempt",a.getLong("id"),d.getLong("group_id")))})
        }
        item { PrimaryButton("+ Новая попытка",!vm.busy&&d.optBoolean("writable")) {
            vm.openForm(dishId=d.getLong("id"),groupId=d.getLong("group_id"))
        } }
    }
}
@Composable fun AttemptScreen(vm:AppState) {
    val a=vm.data ?: return
    val snapshot=a.getJSONObject("snapshot")
    ContentList {
        item { Text(snapshot.text("title"),fontSize=22.sp);Text(a.text("author_name")+" · "+dateText(a.text("created_at")),fontSize=13.sp,color=Muted) }
        if(!a.isNull("parent_id"))item {
            TextButton(onClick={vm.go(Route("attempt",a.getLong("parent_id"),a.getLong("group_id")))},enabled=!vm.busy){Text("Открыть исходную попытку")}
        }
        item { Column(verticalArrangement=Arrangement.spacedBy(14.dp)){RecipeBody(vm,snapshot)} }
        item { InfoCard("Результат приготовления",body=a.text("note")) }
        item { Text("Сохранённая версия не изменяется. Исправление сохраняется новой попыткой.",fontSize=13.sp,color=Muted) }
        item { PrimaryButton("Создать вариант на основе этой",!vm.busy&&a.optBoolean("writable")) {
            vm.openForm(snapshot,a.getLong("dish_id"),a.getLong("group_id"),a.getLong("id"))
        } }
        if(!a.optBoolean("writable"))item { EmptyState("Подписка создателя закончилась","Сохранённые материалы доступны для просмотра.") }
    }
}
@Composable fun InvitationsScreen(vm:AppState) {
    ContentList {
        if(vm.entries.isEmpty()&&!vm.loading&&vm.error==null)item { EmptyState("Новых приглашений нет") }
        items(vm.entries,key={it.getLong("id")}) { inv->
            InfoCard(inv.text("group_title"),"От "+inv.text("sender_name")) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Принять",!vm.busy) { vm.task { vm.api.call("/invitations/"+inv.getLong("id")+"/answer","POST",json("accept" to true));vm.notice="Приглашение принято";vm.refresh() } }
                    TextButton(onClick={vm.task { vm.api.call("/invitations/"+inv.getLong("id")+"/answer","POST",json("accept" to false));vm.notice="Приглашение отклонено";vm.refresh() }},enabled=!vm.busy){Text("Отклонить")}
                }
            }
        }
    }
}
@Composable fun SubscriptionScreen(vm:AppState) {
    val u=vm.data ?: vm.user ?: return
    var expire by remember { mutableStateOf(false) }
    ContentList {
        item { InfoCard(if(u.optBoolean("subscription_active"))"Подписка активна" else "Подписка неактивна",
            if(u.text("subscription_until").isNotEmpty())"До "+dateText(u.text("subscription_until")) else null,
            "Создавайте приватные группы и сохраняйте новые попытки. Приглашённым участникам своя подписка не нужна.") }
        item { PrimaryButton(if(u.optBoolean("subscription_active"))"Продлить на 30 дней" else "Активировать на 30 дней",!vm.busy) {
            vm.task { vm.api.call("/subscription/activate","POST");vm.refreshUser();vm.notice="Подписка продлена на 30 дней";vm.refresh() }
        } }
        item { Text("Демонстрационная подписка. Деньги не списываются.",fontSize=13.sp,color=Muted) }
        if(u.optBoolean("subscription_active"))item { OutlinedButton(onClick={expire=true},enabled=!vm.busy){Text("Завершить подписку сейчас")} }
        item { InfoCard("После окончания",body="История остаётся доступной. Новые блюда и попытки — после продления. Управление участниками сохраняется.") }
    }
    if(expire)ConfirmDialog("Завершить подписку?","В ваших группах станет доступен только просмотр. Подписку можно снова активировать.",{
        expire=false;vm.task { vm.api.call("/subscription/expire","POST");vm.refreshUser();vm.notice="Подписка завершена";vm.refresh() }
    },{expire=false},"Завершить")
}
