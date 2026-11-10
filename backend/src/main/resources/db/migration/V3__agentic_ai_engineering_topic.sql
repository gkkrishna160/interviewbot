-- Add a topic to the existing extensible taxonomy; preserve populated databases.
insert into qb_topic(category_id,name,enabled,created_at,updated_at,version)
select c.id,'Agentic AI Engineering',true,current_timestamp,current_timestamp,0
from qb_category c
where c.name='Engineering Fundamentals'
on duplicate key update name='Agentic AI Engineering';
